package dz.hirz.server.jobs

import dz.hirz.server.assets.ProfileService
import dz.hirz.server.buses.BusService
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.rules.BusFix
import dz.hirz.server.rules.BusRules
import dz.hirz.server.rules.Finding
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.rules.PersonState
import dz.hirz.server.ts
import dz.hirz.shared.api.BusRouteDto
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
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

private val ALGIERS: ZoneId = ZoneId.of("Africa/Algiers")

/** The day a bus rule last fired for one run (and stop), so it fires once per run. */
@Serializable
private data class RunDay(val day: String)

/**
 * The bus rules (P9), every minute, for buses inside one of their runs and not in maintenance. Each rule fires at most
 * once per run and day (`STOP_LATE` once per stop). Outside a run the P4 vehicle rules apply, not these.
 */
class BusScanner(private val db: DataSource, private val clock: Clock, private val cfg: RuleConfig, private val recorder: FindingRecorder) {
    private val log = LoggerFactory.getLogger(BusScanner::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 60_000): Job = scope.launch {
        while (isActive) {
            runCatching { scanOnce() }.onFailure { log.error("bus scan failed", it) }
            delay(everyMs)
        }
    }

    fun scanOnce() {
        val now = clock.instant()
        val buses = db.connection.use { c ->
            c.createStatement().executeQuery(
                """SELECT DISTINCT a.id FROM bus_routes r JOIN assets a ON a.id = r.asset_id
                   WHERE a.lifecycle NOT IN ('paused', 'archived') AND COALESCE(a.mode_override, '') <> 'maintenance'""",
            ).toList { it.getObject(1, UUID::class.java) }
        }
        for (bus in buses) runCatching { db.tx { c -> scan(c, bus, now) }.forEach(recorder::record) }.onFailure { log.error("bus $bus", it) }
    }

    private fun once(c: Connection, bus: UUID, key: String, day: String): Boolean {
        if (PersonState.load(c, bus, key, RunDay.serializer())?.day == day) return false
        PersonState.save(c, bus, key, RunDay.serializer(), RunDay(day))
        return true
    }

    private fun scan(c: Connection, bus: UUID, now: Instant): List<Finding> {
        val route = BusService.running(c, bus, now) ?: return emptyList()
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").apply { setString(1, bus.toString()) }.executeQuery().close()
        val circle = c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, bus) }
            .executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        val profile = ProfileService.loadAt(c, bus, now) ?: return emptyList()
        val local = now.atZone(ALGIERS)
        val day = local.toLocalDate().toString()
        val runStart = local.toLocalDate().atStartOfDay(ALGIERS).plusMinutes(route.startMin.toLong()).toInstant()
        val fixes = fixes(c, bus, maxOf(runStart, now.minusSeconds(15 * 60)))
        val found = mutableListOf<Finding>()
        fun raise(rule: RuleId, key: String, reason: Reason) {
            val tier = profile.tierOf(rule) ?: return
            if (once(c, bus, key, day)) found += Finding(bus, circle, rule, tier, listOf(reason), now)
        }
        if (BusRules.offCorridor(fixes, route.path, route.widthM)) {
            raise(RuleId.ROUTE_DEVIATION, "BUS_DEVIATION:${route.id}", Reason("route_deviation", mapOf("route" to route.name, "distanceM" to BusRules.offBy(fixes, route.path, route.widthM).toString())))
        }
        BusRules.stillOffStops(fixes, route.stops)?.let {
            raise(RuleId.UNSCHEDULED_STOP, "BUS_STOP:${route.id}", Reason("unscheduled_stop", mapOf("minutes" to "5")))
        }
        if (BusRules.overspeed(fixes)) {
            val kmh = fixes.lastOrNull()?.speedKmh?.toInt() ?: 0
            raise(RuleId.OVERSPEED_IN_SERVICE, "BUS_SPEED:${route.id}", Reason("overspeed_in_service", mapOf("speedKmh" to kmh.toString())))
        }
        val reached = reached(c, bus, route, runStart)
        BusRules.lateStop(route.stops, reached, local.hour * 60 + local.minute)?.let { s ->
            raise(RuleId.STOP_LATE, "BUS_LATE:${route.id}:${s.seq}", Reason("stop_late", mapOf("stop" to s.name, "minutes" to (local.hour * 60 + local.minute - s.dueMin).toString())))
        }
        return found
    }

    private fun fixes(c: Connection, bus: UUID, since: Instant): List<BusFix> =
        c.prepareStatement(
            """SELECT fix_time, ST_Y(geog::geometry), ST_X(geog::geometry), COALESCE(accuracy_m, 0), speed_kmh FROM positions
               WHERE asset_id = ? AND quality = 'ok' AND geog IS NOT NULL AND fix_time >= ? AND COALESCE(accuracy_m, 9999) <= ? ORDER BY fix_time""",
        ).apply { setObject(1, bus); setTimestamp(2, since.ts()); setDouble(3, cfg.boundaryAccuracyFloorM) }.executeQuery().toList {
            BusFix(it.getTimestamp(1).toInstant(), dz.hirz.shared.api.LatLon(it.getDouble(2), it.getDouble(3)), it.getDouble(4),
                it.getDouble(5).takeUnless { _ -> it.wasNull() })
        }

    /** Stops the bus has been at since the run began (a good fix within the stop's radius plus its accuracy). */
    fun reached(c: Connection, bus: UUID, route: BusRouteDto, since: Instant): Set<Int> {
        val all = fixes(c, bus, since)
        return route.stops.filter { s -> all.any { f -> BusRules.metres(f.p, s.at) <= s.radiusM + f.accuracyM } }.map { it.seq }.toSet()
    }
}
