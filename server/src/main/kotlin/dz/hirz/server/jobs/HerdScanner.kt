package dz.hirz.server.jobs

import dz.hirz.server.apiJson
import dz.hirz.server.assets.ProfileService
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.places.Geometry
import dz.hirz.server.places.PlaceService
import dz.hirz.server.rules.Finding
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.rules.HerdRules
import dz.hirz.server.rules.PersonState
import dz.hirz.server.ts
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import dz.hirz.shared.rules.RuleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.roundToInt

/** Recent spread samples of a herd and when dispersion last fired. */
@Serializable
private data class HerdState(val samples: List<Pair<Long, Double>> = emptyList(), val firedAtMs: Long? = null)

@Serializable
private data class StillState(val firedAtMs: Long)

private const val STILL = "ANIMAL_STILL"

/**
 * Herd-wide and slow livestock rules (design §12.2), every few minutes: `HERD_DISPERSION` (the spread of a herd's
 * fresh fixes jumps) and `ANIMAL_STILL` (an animal that has not moved for hours, with enough fixes to say so).
 */
class HerdScanner(private val db: DataSource, private val clock: Clock, private val cfg: RuleConfig, private val recorder: FindingRecorder) {
    private val log = LoggerFactory.getLogger(HerdScanner::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 5 * 60_000): Job = scope.launch {
        while (isActive) {
            runCatching { scanOnce() }.onFailure { log.error("herd scan failed", it) }
            delay(everyMs)
        }
    }

    fun scanOnce() {
        val now = clock.instant()
        val herds = db.connection.use { c ->
            c.prepareStatement("SELECT id, circle_id, leader_asset FROM asset_groups WHERE kind = 'herd'").executeQuery()
                .toList { Triple(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getObject(3, UUID::class.java)) }
        }
        herds.forEach { (herd, circle, leader) -> runCatching { dispersion(herd, circle, leader, now) }.onFailure { log.error("dispersion $herd", it) } }
        val animals = db.connection.use { c ->
            c.prepareStatement("SELECT id, circle_id FROM assets WHERE type = 'livestock' AND lifecycle NOT IN ('paused', 'archived')").executeQuery()
                .toList { it.getObject(1, UUID::class.java) to it.getObject(2, UUID::class.java) }
        }
        animals.forEach { (asset, circle) -> runCatching { still(asset, circle, now) }.onFailure { log.error("stillness $asset", it) } }
    }

    private fun lastFixes(c: Connection, assets: List<UUID>, since: Instant): Map<UUID, LatLon> = assets.mapNotNull { a ->
        c.prepareStatement(
            """SELECT ST_Y(geog::geometry), ST_X(geog::geometry) FROM positions WHERE asset_id = ? AND quality = 'ok' AND geog IS NOT NULL
               AND fix_time >= ? AND accuracy_m <= ? ORDER BY fix_time DESC LIMIT 1""",
        ).apply { setObject(1, a); setTimestamp(2, since.ts()); setDouble(3, cfg.boundaryAccuracyFloorM) }
            .executeQuery().use { if (it.next()) a to LatLon(it.getDouble(1), it.getDouble(2)) else null }
    }.toMap()

    private fun dispersion(herd: UUID, circle: UUID, leader: UUID?, now: Instant) {
        val finding = db.tx { c ->
            c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").apply { setString(1, herd.toString()) }.executeQuery().close()
            val members = c.prepareStatement("SELECT asset_id FROM asset_group_members WHERE group_id = ? AND kind = 'herd'")
                .apply { setObject(1, herd) }.executeQuery().toList { it.getObject(1, UUID::class.java) }
            val target = leader ?: members.firstOrNull() ?: return@tx null
            val profile = ProfileService.load(c, target) ?: return@tx null
            val fixes = lastFixes(c, members, now.minusMillis(cfg.herdFixMaxAgeMs))
            val st = c.prepareStatement("SELECT state::text FROM herd_rule_state WHERE herd_id = ?").apply { setObject(1, herd) }
                .executeQuery().use { if (it.next()) apiJson.decodeFromString(HerdState.serializer(), it.getString(1)) else HerdState() }
            val nowMs = now.toEpochMilli()
            val rg = if (fixes.size >= cfg.herdMinMembers) HerdRules.radiusOfGyrationM(fixes.values.toList()) else null
            val window = st.samples.filter { nowMs - it.first <= cfg.herdDispersionWindowMs }
            val samples = if (rg != null) window + (nowMs to rg) else window
            val night = Geometry.isActive(profile.nightWindow, now)
            val tier = if (night) Tier.P1 else profile.tierOf(RuleId.HERD_DISPERSION)
            val quiet = st.firedAtMs != null && nowMs - st.firedAtMs < cfg.herdDispersionQuietMs
            val fire = rg != null && tier != null && !quiet && HerdRules.dispersed(window.map { it.second }, rg, cfg)
            val next = HerdState(samples, if (fire) nowMs else st.firedAtMs)
            c.prepareStatement(
                """INSERT INTO herd_rule_state (herd_id, state) VALUES (?, ?::jsonb)
                   ON CONFLICT (herd_id) DO UPDATE SET state = EXCLUDED.state""",
            ).apply { setObject(1, herd); setString(2, apiJson.encodeToString(HerdState.serializer(), next)) }.executeUpdate()
            if (!fire || profile.tierOf(RuleId.HERD_DISPERSION) == null) return@tx null
            Finding(target, circle, RuleId.HERD_DISPERSION, tier!!, listOf(Reason("herd_dispersed", mapOf(
                "spreadM" to rg!!.roundToInt().toString(), "heads" to fixes.size.toString(),
            ))), now)
        } ?: return
        recorder.record(finding)
    }

    private fun still(asset: UUID, circle: UUID, now: Instant) {
        val finding = db.tx { c ->
            c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").apply { setString(1, asset.toString()) }.executeQuery().close()
            val profile = ProfileService.load(c, asset) ?: return@tx null
            val tier = profile.tierOf(RuleId.ANIMAL_STILL) ?: return@tx null
            if (Geometry.isActive(profile.nightWindow, now)) return@tx null
            val last = PersonState.load(c, asset, STILL, StillState.serializer())
            if (last != null && now.toEpochMilli() - last.firedAtMs < 24 * 3_600_000L) return@tx null
            val track = c.prepareStatement(
                """SELECT fix_time, ST_Y(geog::geometry), ST_X(geog::geometry) FROM positions WHERE asset_id = ? AND quality = 'ok'
                   AND geog IS NOT NULL AND fix_time >= ? AND accuracy_m <= ? ORDER BY fix_time""",
            ).apply { setObject(1, asset); setTimestamp(2, now.minusMillis(cfg.stillMs + 3_600_000L).ts()); setDouble(3, cfg.boundaryAccuracyFloorM) }
                .executeQuery().toList { it.getTimestamp(1).toInstant().toEpochMilli() to LatLon(it.getDouble(2), it.getDouble(3)) }
            if (!HerdRules.still(track, cfg)) return@tx null
            // Only where an animal belongs: a still head on a road is a different story (and its own rules).
            val here = track.last().second
            val inPlace = PlaceService.placesOf(c, circle).filter { it.kind == PlaceKind.PASTURE || it.kind == PlaceKind.BARN }
                .any { Geometry.distanceOutsideM(c, it.shape, here) <= 0.0 }
            if (!inPlace) return@tx null
            PersonState.save(c, asset, STILL, StillState.serializer(), StillState(now.toEpochMilli()))
            Finding(asset, circle, RuleId.ANIMAL_STILL, tier, listOf(Reason("animal_still", mapOf("hours" to (cfg.stillMs / 3_600_000L).toString()))), now)
        } ?: return
        recorder.record(finding)
    }
}
