package dz.hirz.server.jobs

import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.escalation.OwnerOrder
import dz.hirz.server.places.Geometry
import dz.hirz.server.places.PlaceService
import dz.hirz.server.ts
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.rules.RuleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * The smart layer (design §4.5): each night, the places an asset stays at regularly are learned from its own track.
 * A dwell is [RuleConfig.learnDwellMs] of good fixes within [RuleConfig.learnRadiusM]; a place is dwelt at on
 * [RuleConfig.learnDays] different days within the learning window. Places the circle already named are not relearned.
 * Learned places only ever support a finding ("a place never visited") or a digest line; they never raise anything.
 */
class LearningJob(private val db: DataSource, private val clock: Clock, private val cfg: RuleConfig) {
    private val log = LoggerFactory.getLogger(LearningJob::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 24 * 3_600_000L): Job = scope.launch {
        while (isActive) {
            runCatching { runOnce() }.onFailure { log.error("learning failed", it) }
            delay(everyMs)
        }
    }

    private data class Dwell(val at: LatLon, val day: String, val endMs: Long)
    private class Cluster(var at: LatLon, val days: MutableSet<String>, var lastMs: Long, var visits: Int)

    fun runOnce() {
        val now = clock.instant()
        val since = now.minusMillis(cfg.learnWindowMs)
        val assets = db.connection.use { c ->
            c.prepareStatement("SELECT DISTINCT asset_id FROM positions WHERE fix_time >= ?").apply { setTimestamp(1, since.ts()) }
                .executeQuery().toList { it.getObject(1, UUID::class.java) }
        }
        for (asset in assets) runCatching { learn(asset, since) }.onFailure { log.error("learning $asset", it) }
    }

    private fun learn(asset: UUID, since: Instant) = db.tx { c ->
        val circle = c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, asset) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else return@tx }
        val track = c.prepareStatement(
            """SELECT fix_time, ST_Y(geog::geometry), ST_X(geog::geometry) FROM positions WHERE asset_id = ? AND quality = 'ok' AND geog IS NOT NULL
               AND fix_time >= ? AND COALESCE(accuracy_m, 9999) <= ? ORDER BY fix_time""",
        ).apply { setObject(1, asset); setTimestamp(2, since.ts()); setDouble(3, cfg.boundaryAccuracyFloorM) }
            .executeQuery().toList { it.getTimestamp(1).toInstant() to LatLon(it.getDouble(2), it.getDouble(3)) }
        val dwells = mutableListOf<Dwell>()
        var i = 0
        while (i < track.size) {
            var j = i
            // A dwell is one continuous stay: a gap in the track (another day's visit to the same spot) ends it.
            while (j + 1 < track.size && OwnerOrder.metres(track[i].second, track[j + 1].second) <= cfg.learnRadiusM &&
                java.time.Duration.between(track[j].first, track[j + 1].first).toMillis() <= DWELL_GAP_MS) j++
            val span = java.time.Duration.between(track[i].first, track[j].first).toMillis()
            if (span >= cfg.learnDwellMs) {
                val part = track.subList(i, j + 1)
                val centre = LatLon(part.map { it.second.lat }.average(), part.map { it.second.lon }.average())
                dwells += Dwell(centre, track[i].first.atZone(ZoneId.of("Africa/Algiers")).toLocalDate().toString(), track[j].first.toEpochMilli())
            }
            i = j + 1
        }
        val clusters = mutableListOf<Cluster>()
        for (d in dwells) {
            val hit = clusters.firstOrNull { OwnerOrder.metres(it.at, d.at) <= cfg.learnRadiusM }
            if (hit == null) clusters += Cluster(d.at, mutableSetOf(d.day), d.endMs, 1)
            else { hit.days += d.day; hit.lastMs = maxOf(hit.lastMs, d.endMs); hit.visits++ }
        }
        val named = PlaceService.placesOf(c, circle)
        val learned = clusters.filter { it.days.size >= cfg.learnDays }
            .filter { cl -> named.none { Geometry.distanceOutsideM(c, it.shape, cl.at) <= 0.0 } }
        c.prepareStatement("DELETE FROM learned_places WHERE asset_id = ?").apply { setObject(1, asset) }.executeUpdate()
        for (cl in learned) {
            c.prepareStatement(
                "INSERT INTO learned_places (asset_id, lat, lon, radius_m, days, visits, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).apply {
                setObject(1, asset); setDouble(2, cl.at.lat); setDouble(3, cl.at.lon); setDouble(4, cfg.learnRadiusM)
                setInt(5, cl.days.size); setInt(6, cl.visits); setTimestamp(7, Instant.ofEpochMilli(cl.lastMs).ts())
            }.executeUpdate()
        }
    }

    companion object {
        /** Fixes further apart than this are two stays, not one. */
        private const val DWELL_GAP_MS = 3_600_000L

        /** Whether [at] is within an asset's learned places. */
        fun known(c: java.sql.Connection, assetId: UUID, at: LatLon, accuracyM: Double): Boolean =
            c.prepareStatement(
                """SELECT EXISTS (SELECT 1 FROM learned_places WHERE asset_id = ?
                     AND ST_DWithin(ST_SetSRID(ST_MakePoint(lon, lat), 4326)::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, radius_m + ?))""",
            ).apply { setObject(1, assetId); setDouble(2, at.lon); setDouble(3, at.lat); setDouble(4, accuracyM) }
                .executeQuery().use { it.next() && it.getBoolean(1) }
    }
}
