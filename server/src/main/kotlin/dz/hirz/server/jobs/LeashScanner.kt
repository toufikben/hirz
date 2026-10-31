package dz.hirz.server.jobs

import dz.hirz.server.assets.ProfileService
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.escalation.OwnerOrder
import dz.hirz.server.presence.TOGETHER_M
import dz.hirz.server.rules.Finding
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.ts
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.rules.RuleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.roundToInt

/**
 * `LEASH_BREAK` (design §12.2), every 15 s during accompanied outings. Together means the guardian's phone heard the
 * person's tag (recorded by the ping) or a good fresh fix of the person is within 50 m of the guardian, net of its
 * accuracy. Apart for [RuleConfig.leashMs] raises it when:
 * - a good fresh fix puts the person more than 50 m away, net of accuracy; or
 * - the person carries a tag, the guardian's phone stopped hearing it, and no fresh fix says where they are (lost and
 *   silent, the case that matters most).
 * A guardian whose phone stopped pinging says nothing (no alarm from a dead phone); a reunion re-arms the rule.
 */
class LeashScanner(private val db: DataSource, private val clock: Clock, private val cfg: RuleConfig, private val recorder: FindingRecorder) {
    private val log = LoggerFactory.getLogger(LeashScanner::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 15_000): Job = scope.launch {
        while (isActive) {
            runCatching { scanOnce() }.onFailure { log.error("leash scan failed", it) }
            delay(everyMs)
        }
    }

    fun scanOnce() {
        val now = clock.instant()
        db.tx { c ->
            c.prepareStatement("UPDATE outings SET ended_at = ends_at WHERE ended_at IS NULL AND ends_at <= ?").apply { setTimestamp(1, now.ts()) }.executeUpdate()
        }
        data class Row(val id: UUID, val asset: UUID, val circle: UUID)
        val active = db.connection.use { c ->
            c.prepareStatement(
                """SELECT o.id, o.asset_id, a.circle_id FROM outings o JOIN assets a ON a.id = o.asset_id
                   WHERE o.ended_at IS NULL AND o.last_ping_at >= ?""",
            ).apply { setTimestamp(1, now.minusMillis(cfg.leashPingMaxAgeMs).ts()) }
                .executeQuery().toList { Row(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getObject(3, UUID::class.java)) }
        }
        for (r in active) {
            val finding = db.tx { c ->
                c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").apply { setString(1, r.asset.toString()) }.executeQuery().close()
                data class O(val guardian: LatLon, val togetherAtMs: Long, val raised: Boolean, val tagHeard: Boolean)
                val o = c.prepareStatement(
                    "SELECT last_lat, last_lon, together_at, raised_at IS NOT NULL, tag_heard_at IS NOT NULL FROM outings WHERE id = ? AND ended_at IS NULL FOR UPDATE",
                ).apply { setObject(1, r.id) }.executeQuery().use {
                    if (it.next()) O(LatLon(it.getDouble(1), it.getDouble(2)), it.getTimestamp(3).time, it.getBoolean(4), it.getBoolean(5)) else null
                }
                    ?: return@tx null
                // The person's newest fix, if fresh and good enough to tell 50 m apart.
                data class Fix(val at: LatLon, val accuracyM: Double)
                val fix = c.prepareStatement(
                    """SELECT ST_Y(geog::geometry), ST_X(geog::geometry), COALESCE(accuracy_m, 9999) FROM positions WHERE asset_id = ? AND quality = 'ok'
                       AND geog IS NOT NULL AND fix_time >= ? ORDER BY fix_time DESC LIMIT 1""",
                ).apply { setObject(1, r.asset); setTimestamp(2, now.minusMillis(cfg.leashPingMaxAgeMs).ts()) }
                    .executeQuery().use { if (it.next()) Fix(LatLon(it.getDouble(1), it.getDouble(2)), it.getDouble(3)) else null }
                    ?.takeIf { it.accuracyM <= cfg.boundaryAccuracyFloorM }
                val apartM = fix?.let { OwnerOrder.metres(o.guardian, it.at) - it.accuracyM }
                if (apartM != null && apartM <= TOGETHER_M) {
                    c.prepareStatement("UPDATE outings SET together_at = ?, raised_at = NULL WHERE id = ?").apply { setTimestamp(1, now.ts()); setObject(2, r.id) }.executeUpdate()
                    return@tx null
                }
                if (o.raised || now.toEpochMilli() - o.togetherAtMs < cfg.leashMs) return@tx null
                // "Lost contact" only where this guardian's phone did hear the tag this outing: a phone that cannot scan
                // never heard it, and its silence means nothing.
                val tagged = o.tagHeard
                val reason = when {
                    apartM != null -> Reason("leash_break", mapOf("distanceM" to apartM.roundToInt().toString(),
                        "seconds" to ((now.toEpochMilli() - o.togetherAtMs) / 1000).toString()))
                    tagged -> Reason("leash_lost_contact", mapOf("seconds" to ((now.toEpochMilli() - o.togetherAtMs) / 1000).toString()))
                    // Untagged and silent: the phone's own silence rule speaks; a guess here would be a false P0.
                    else -> return@tx null
                }
                val tier = ProfileService.loadAt(c, r.asset, now)?.tierOf(RuleId.LEASH_BREAK) ?: return@tx null
                c.prepareStatement("UPDATE outings SET raised_at = ? WHERE id = ?").apply { setTimestamp(1, now.ts()); setObject(2, r.id) }.executeUpdate()
                Finding(r.asset, r.circle, RuleId.LEASH_BREAK, tier, listOf(reason), now)
            } ?: continue
            recorder.record(finding)
        }
    }
}
