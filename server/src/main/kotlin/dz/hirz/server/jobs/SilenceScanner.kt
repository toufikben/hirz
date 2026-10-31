package dz.hirz.server.jobs

import dz.hirz.server.rules.Finding
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
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

/**
 * Silence is a signal (design §4.3 SILENT_SOURCE). `dead`: no heartbeat for [RuleConfig.silentDeadMs];
 * `alive`: heartbeats but no fix for [RuleConfig.silentAliveNoFixMs]. One finding per silence episode;
 * during an open P0/P1 incident the silence is added to that incident instead.
 */
class SilenceScanner(
    private val db: DataSource,
    private val clock: Clock,
    private val cfg: RuleConfig,
    private val recorder: FindingRecorder,
) {
    private val log = LoggerFactory.getLogger(SilenceScanner::class.java)
    /** Stores [kind] as the source's episode; true only when it changed to a new silence. */
    private fun episode(source: UUID, kind: String?): Boolean = db.connection.use { c ->
        c.prepareStatement(
            "UPDATE sources SET silence_episode = ? WHERE id = ? AND silence_episode IS DISTINCT FROM ?",
        ).apply { setString(1, kind); setObject(2, source); setString(3, kind) }.executeUpdate() == 1
    }

    fun start(scope: CoroutineScope, everyMs: Long = 30_000): Job = scope.launch {
        while (isActive) {
            runCatching { scanOnce() }.onFailure { log.error("silence scan failed", it) }
            delay(everyMs)
        }
    }

    fun scanOnce() {
        val now = clock.instant()
        data class Row(val source: UUID, val asset: UUID, val circle: UUID, val heartbeatMs: Long?, val fixMs: Long?, val createdMs: Long)
        val rows = db.connection.use { c ->
            c.createStatement().executeQuery(
                """SELECT s.id, s.asset_id, a.circle_id, s.last_heartbeat_at, s.last_fix_at, s.created_at
                   FROM sources s JOIN assets a ON a.id = s.asset_id
                   WHERE s.revoked_at IS NULL AND s.paused_reason IS NULL AND s.kind <> 'ble' AND a.lifecycle NOT IN ('paused', 'archived')""",
            ).use { rs ->
                buildList {
                    while (rs.next()) add(Row(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java),
                        rs.getObject(3, UUID::class.java), rs.getTimestamp(4)?.time, rs.getTimestamp(5)?.time, rs.getTimestamp(6).time))
                }
            }
        }
        val nowMs = now.toEpochMilli()
        for (r in rows) {
            val sinceHeartbeat = nowMs - (r.heartbeatMs ?: r.createdMs)
            val sinceFix = nowMs - (r.fixMs ?: r.createdMs)
            // A profile may wait longer: livestock in pastures without coverage is quiet for hours (P6).
            val wait = db.connection.use { c -> dz.hirz.server.assets.ProfileService.load(c, r.asset) }?.silenceAfterMs
            val kind = when {
                sinceHeartbeat > (wait ?: cfg.silentDeadMs) -> "dead"
                sinceFix > (wait ?: cfg.silentAliveNoFixMs) -> "alive"
                else -> null
            }
            // The episode lives in the database, so a restart never raises the same silence twice (P1 minor #17).
            if (!episode(r.source, kind)) continue
            if (kind == null) continue
            val minutes = ((if (kind == "dead") sinceHeartbeat else sinceFix) / 60_000).toString()
            if (recorder.appendToOpen(r.asset, Reason("silence_during_incident", mapOf("mode" to kind, "minutes" to minutes)))) continue
            recorder.record(Finding(r.asset, r.circle, RuleId.SILENT_SOURCE, Tier.P2, listOf(Reason("silent_$kind", mapOf("minutes" to minutes))), now))
        }
    }
}
