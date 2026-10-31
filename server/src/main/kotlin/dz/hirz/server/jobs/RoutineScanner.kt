package dz.hirz.server.jobs

import dz.hirz.server.assets.ProfileService
import dz.hirz.server.calendar.AlgerianCalendar
import dz.hirz.server.custody.Custody
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.rules.Finding
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.rules.PersonState
import dz.hirz.server.routines.RoutineService
import dz.hirz.server.ts
import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.RoutineDto
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

private val ALGIERS: ZoneId = ZoneId.of("Africa/Algiers")

/** The day a routine rule last fired, so it fires once a day. */
@Serializable
private data class RoutineDay(val day: String)

/**
 * Routine rules that are about something not happening (design §4.3), every minute: a missed arrival has no report to
 * trigger it. Each routine fires at most once a day.
 */
class RoutineScanner(private val db: DataSource, private val clock: Clock, private val cfg: RuleConfig, private val recorder: FindingRecorder) {
    private val log = LoggerFactory.getLogger(RoutineScanner::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 60_000): Job = scope.launch {
        while (isActive) {
            runCatching { scanOnce() }.onFailure { log.error("routine scan failed", it) }
            delay(everyMs)
        }
    }

    fun scanOnce() {
        val now = clock.instant()
        val routines = db.connection.use { RoutineService.active(it) }
        for (r in routines) {
            runCatching { missedArrival(r, now)?.let(recorder::record) }.onFailure { log.error("routine ${r.id}", it) }
        }
    }

    /** Whether the routine runs on [day]: its weekdays and, for a school, the school calendar (with the circle's corrections). */
    private fun runsOn(c: Connection, r: RoutineDto, circle: UUID, day: LocalDate): Boolean {
        if (day.dayOfWeek.value !in r.days) return false
        val kind = c.prepareStatement("SELECT kind FROM places WHERE id = ?").apply { setObject(1, UUID.fromString(r.placeId)) }
            .executeQuery().use { if (it.next()) it.getString(1) else return false }
        if (kind != "school") return !AlgerianCalendar.isNationalHoliday(day)
        val overrides = c.prepareStatement("SELECT kind, day FROM calendar_overrides WHERE circle_id = ?").apply { setObject(1, circle) }
            .executeQuery().toList { it.getString(1) to it.getDate(2).toLocalDate() }
        return AlgerianCalendar.isSchoolDay(day, overrides)
    }

    /**
     * `MISSED_ARRIVAL`: [RuleConfig.arrivalGraceMin] after the arrive-by time, no good fix inside the place since the
     * arrival window opened. Not when the asset's sources are silent (that is the silence rule's to say), and not during
     * a handover that mutes place exits (a day with the other parent).
     */
    private fun missedArrival(r: RoutineDto, now: Instant): Finding? = db.tx { c ->
        val arriveBy = r.arriveByMin ?: return@tx null
        val asset = UUID.fromString(r.assetId)
        val local = now.atZone(ALGIERS)
        val minute = local.hour * 60 + local.minute
        if (minute < arriveBy + cfg.arrivalGraceMin || minute > arriveBy + cfg.arrivalGraceMin + 180) return@tx null
        val circle = c.prepareStatement("SELECT circle_id FROM assets WHERE id = ? AND lifecycle NOT IN ('paused', 'archived')")
            .apply { setObject(1, asset) }.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null } ?: return@tx null
        if (!runsOn(c, r, circle, local.toLocalDate())) return@tx null
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").apply { setString(1, asset.toString()) }.executeQuery().close()
        val key = "ROUTINE_ARRIVAL:${r.id}"
        if (PersonState.load(c, asset, key, RoutineDay.serializer())?.day == local.toLocalDate().toString()) return@tx null
        val tier = ProfileService.loadAt(c, asset, now)?.tierOf(RuleId.MISSED_ARRIVAL) ?: return@tx null
        val fresh = c.prepareStatement(
            "SELECT max(GREATEST(COALESCE(last_heartbeat_at, last_fix_at), COALESCE(last_fix_at, last_heartbeat_at))) >= ? FROM sources WHERE asset_id = ? AND revoked_at IS NULL",
        ).apply { setTimestamp(1, now.minusMillis(cfg.routineSourceFreshMs).ts()); setObject(2, asset) }.executeQuery().use { it.next() && it.getBoolean(1) }
        if (!fresh) return@tx null
        if (Custody.muted(c, asset, now)) return@tx null
        val windowStart = local.toLocalDate().atStartOfDay(ALGIERS).plusMinutes((arriveBy - 180L).coerceAtLeast(0)).toInstant()
        val arrived = c.prepareStatement(
            """SELECT EXISTS (SELECT 1 FROM positions p, places pl WHERE pl.id = ? AND p.asset_id = ? AND p.quality = 'ok' AND p.geog IS NOT NULL
                 AND p.fix_time >= ? AND COALESCE(p.accuracy_m, 0) <= ? AND
                 CASE WHEN GeometryType(pl.geom::geometry) = 'POINT' THEN ST_DWithin(p.geog, pl.geom, (pl.shape->>'radiusM')::float8 + COALESCE(p.accuracy_m, 0))
                      ELSE ST_DWithin(pl.geom, p.geog, COALESCE(p.accuracy_m, 0)) END)""",
        ).apply {
            setObject(1, UUID.fromString(r.placeId)); setObject(2, asset); setTimestamp(3, windowStart.ts()); setDouble(4, cfg.boundaryAccuracyFloorM)
        }.executeQuery().use { it.next() && it.getBoolean(1) }
        if (arrived) return@tx null
        PersonState.save(c, asset, key, RoutineDay.serializer(), RoutineDay(local.toLocalDate().toString()))
        val place = c.prepareStatement("SELECT name FROM places WHERE id = ?").apply { setObject(1, UUID.fromString(r.placeId)) }
            .executeQuery().use { it.next(); it.getString(1) }
        Finding(asset, circle, RuleId.MISSED_ARRIVAL, tier, listOf(Reason("missed_arrival", mapOf(
            "place" to place, "by" to "%02d:%02d".format(arriveBy / 60, arriveBy % 60),
        ))), now)
    }
}
