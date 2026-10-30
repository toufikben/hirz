package dz.hirz.server.jobs

import dz.hirz.server.assets.ProfileService
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.places.Geometry
import dz.hirz.server.places.PlaceService
import dz.hirz.server.rules.Finding
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.rules.PersonState
import dz.hirz.server.ts
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.rules.RuleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

private val ALGIERS: ZoneId = ZoneId.of("Africa/Algiers")
private const val CURFEW = "CURFEW"

/** The night a curfew was last raised for, as the local date of its evening. */
@Serializable
private data class CurfewState(val night: String)

/**
 * `CURFEW` (design §12.2): a teen still outside every home and relative's place [RuleConfig.curfewGraceMin] after
 * their curfew, until 06:00. Raised once per night, from the newest fresh fix; no fix says nothing (silence has its rule).
 */
class CurfewScanner(private val db: DataSource, private val clock: Clock, private val cfg: RuleConfig, private val recorder: FindingRecorder) {
    private val log = LoggerFactory.getLogger(CurfewScanner::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 60_000): Job = scope.launch {
        while (isActive) {
            runCatching { scanOnce() }.onFailure { log.error("curfew scan failed", it) }
            delay(everyMs)
        }
    }

    fun scanOnce() {
        val now = clock.instant()
        val local = now.atZone(ALGIERS)
        val minute = local.hour * 60 + local.minute
        // The evening this night belongs to: before noon it is still last night.
        val night = (if (local.hour < 12) local.toLocalDate().minusDays(1) else local.toLocalDate()).toString()
        data class Row(val asset: UUID, val circle: UUID, val curfew: Int)
        val rows = db.connection.use { c ->
            c.prepareStatement(
                "SELECT id, circle_id, curfew_minute FROM assets WHERE curfew_minute IS NOT NULL AND lifecycle NOT IN ('paused', 'archived')",
            ).executeQuery().toList { Row(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getInt(3)) }
        }
        for (r in rows) {
            if (!due(r.curfew, minute)) continue
            val finding = db.tx { c ->
                c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").apply { setString(1, r.asset.toString()) }.executeQuery().close()
                val tier = ProfileService.load(c, r.asset)?.tierOf(RuleId.CURFEW) ?: return@tx null
                if (PersonState.load(c, r.asset, CURFEW, CurfewState.serializer())?.night == night) return@tx null
                val fix = c.prepareStatement(
                    """SELECT ST_Y(geog::geometry), ST_X(geog::geometry), accuracy_m FROM positions
                       WHERE asset_id = ? AND quality = 'ok' AND geog IS NOT NULL AND fix_time >= ? ORDER BY fix_time DESC LIMIT 1""",
                ).apply { setObject(1, r.asset); setTimestamp(2, now.minusMillis(cfg.curfewFixMaxAgeMs).ts()) }
                    .executeQuery().use { if (it.next()) Triple(it.getDouble(1), it.getDouble(2), it.getDouble(3)) else null } ?: return@tx null
                val point = LatLon(fix.first, fix.second)
                val safe = PlaceService.placesOf(c, r.circle).filter { it.kind == PlaceKind.HOME || it.kind == PlaceKind.RELATIVE }
                // Inside within the fix's own accuracy counts as home: a curfew is not worth a false alarm at the door.
                if (safe.any { Geometry.distanceOutsideM(c, it.shape, point) <= fix.third }) return@tx null
                PersonState.save(c, r.asset, CURFEW, CurfewState.serializer(), CurfewState(night))
                Finding(r.asset, r.circle, RuleId.CURFEW, tier, listOf(Reason("curfew", mapOf("curfew" to "%02d:%02d".format(r.curfew / 60, r.curfew % 60)))), now)
            } ?: continue
            recorder.record(finding)
        }
    }

    /** From curfew + grace (possibly past midnight) until 06:00. */
    private fun due(curfew: Int, minute: Int): Boolean {
        val start = (curfew + cfg.curfewGraceMin) % 1440
        val end = 6 * 60
        return if (start <= end) minute in start until end else minute >= start || minute < end
    }
}
