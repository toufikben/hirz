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
            runCatching {
                missedArrival(r, now)?.let(recorder::record)
                afterDismissal(r, now)?.let(recorder::record)
                onTheWay(r, now)?.let(recorder::record)
            }.onFailure { log.error("routine ${r.id}", it) }
        }
    }


    /**
     * `MISSED_ARRIVAL`: [RuleConfig.arrivalGraceMin] after the arrive-by time, no good fix inside the place since the
     * arrival window opened. Not when the asset's sources are silent (that is the silence rule's to say), and not during
     * a handover that mutes place exits (a day with the other parent).
     */

    private fun missedArrival(raw: RoutineDto, now: Instant): Finding? = db.tx { c ->
        val r = shifted(raw, now)
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

    /**
     * The asset has stood still for [spanMs] somewhere unknown: at least 3 good fixes spanning it, all within
     * [RuleConfig.routineStillRadiusM] of their centre, and that centre outside every place of the circle. The centre, or null.
     */
    private fun stillUnknown(c: Connection, asset: UUID, circle: UUID, now: Instant, spanMs: Long): dz.hirz.shared.api.LatLon? {
        val fixes = c.prepareStatement(
            """SELECT fix_time, ST_Y(geog::geometry), ST_X(geog::geometry) FROM positions WHERE asset_id = ? AND quality = 'ok' AND geog IS NOT NULL
               AND fix_time >= ? AND COALESCE(accuracy_m, 9999) <= ? ORDER BY fix_time""",
        ).apply { setObject(1, asset); setTimestamp(2, now.minusMillis(spanMs + 5 * 60_000L).ts()); setDouble(3, cfg.boundaryAccuracyFloorM) }
            .executeQuery().toList { it.getTimestamp(1).toInstant() to dz.hirz.shared.api.LatLon(it.getDouble(2), it.getDouble(3)) }
        if (fixes.size < 3 || java.time.Duration.between(fixes.first().first, fixes.last().first).toMillis() < spanMs) return null
        val centre = dz.hirz.shared.api.LatLon(fixes.map { it.second.lat }.average(), fixes.map { it.second.lon }.average())
        if (fixes.any { dz.hirz.server.escalation.OwnerOrder.metres(centre, it.second) > cfg.routineStillRadiusM }) return null
        val known = dz.hirz.server.places.PlaceService.placesOf(c, circle).any { dz.hirz.server.places.Geometry.distanceOutsideM(c, it.shape, centre) <= 0.0 }
        return if (known) null else centre
    }

    private fun once(c: Connection, asset: UUID, key: String, day: LocalDate): Boolean {
        if (PersonState.load(c, asset, key, RoutineDay.serializer())?.day == day.toString()) return false
        PersonState.save(c, asset, key, RoutineDay.serializer(), RoutineDay(day.toString()))
        return true
    }

    private fun circleIfLive(c: Connection, asset: UUID): UUID? =
        c.prepareStatement("SELECT circle_id FROM assets WHERE id = ? AND lifecycle NOT IN ('paused', 'archived')")
            .apply { setObject(1, asset) }.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }

    /** `STATIONARY_AFTER_DISMISSAL` (v2 appendix C-1): from dismissal + 5 min, 15 min still somewhere unknown. Once a day. */
    private fun afterDismissal(raw: RoutineDto, now: Instant): Finding? = db.tx { c ->
        val r = shifted(raw, now)
        val dismissal = r.dismissalMin ?: return@tx null
        val asset = UUID.fromString(r.assetId)
        val local = now.atZone(ALGIERS)
        val minute = local.hour * 60 + local.minute
        val from = dismissal + 5 + (cfg.dismissalStillMs / 60_000).toInt()
        if (minute < from || minute > dismissal + 120) return@tx null
        val circle = circleIfLive(c, asset) ?: return@tx null
        if (!runsOn(c, r, circle, local.toLocalDate()) || Custody.muted(c, asset, now)) return@tx null
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").apply { setString(1, asset.toString()) }.executeQuery().close()
        val tier = ProfileService.loadAt(c, asset, now)?.tierOf(RuleId.STATIONARY_AFTER_DISMISSAL) ?: return@tx null
        stillUnknown(c, asset, circle, now, cfg.dismissalStillMs) ?: return@tx null
        if (!once(c, asset, "ROUTINE_DISMISSAL:${r.id}", local.toLocalDate())) return@tx null
        Finding(asset, circle, RuleId.STATIONARY_AFTER_DISMISSAL, tier, listOf(Reason("stationary_after_dismissal", mapOf(
            "minutes" to (cfg.dismissalStillMs / 60_000).toString(),
        ))), now)
    }

    /** `UNUSUAL_STOP` (P2): on the way to the routine's place (the 90 min before arrive-by, not yet arrived), 10 min still somewhere unknown. */
    private fun onTheWay(raw: RoutineDto, now: Instant): Finding? = db.tx { c ->
        val r = shifted(raw, now)
        val arriveBy = r.arriveByMin ?: return@tx null
        val asset = UUID.fromString(r.assetId)
        val local = now.atZone(ALGIERS)
        val minute = local.hour * 60 + local.minute
        if (minute < arriveBy - 90 || minute > arriveBy) return@tx null
        val circle = circleIfLive(c, asset) ?: return@tx null
        if (!runsOn(c, r, circle, local.toLocalDate()) || Custody.muted(c, asset, now)) return@tx null
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").apply { setString(1, asset.toString()) }.executeQuery().close()
        val tier = ProfileService.loadAt(c, asset, now)?.tierOf(RuleId.UNUSUAL_STOP) ?: return@tx null
        stillUnknown(c, asset, circle, now, cfg.unusualStopMs) ?: return@tx null
        if (!once(c, asset, "ROUTINE_STOP:${r.id}", local.toLocalDate())) return@tx null
        Finding(asset, circle, RuleId.UNUSUAL_STOP, tier, listOf(Reason("unusual_stop", mapOf("minutes" to (cfg.unusualStopMs / 60_000).toString()))), now)
    }

    companion object {
        /** Whether the routine runs on [day]: its weekdays and, for a school, the school calendar (with the circle's corrections). */
        internal fun runsOn(c: Connection, r: RoutineDto, circle: UUID, day: LocalDate): Boolean {
            if (day.dayOfWeek.value !in r.days) return false
            val kind = c.prepareStatement("SELECT kind FROM places WHERE id = ?").apply { setObject(1, UUID.fromString(r.placeId)) }
                .executeQuery().use { if (it.next()) it.getString(1) else return false }
            if (kind != "school") return !AlgerianCalendar.isNationalHoliday(day)
            val overrides = c.prepareStatement("SELECT kind, day FROM calendar_overrides WHERE circle_id = ?").apply { setObject(1, circle) }
                .executeQuery().toList { it.getString(1) to it.getDate(2).toLocalDate() }
            return AlgerianCalendar.isSchoolDay(day, overrides)
        }

        /** The routine as it runs today: shifted by its Ramadan offset during Ramadan. */
        internal fun shifted(r: RoutineDto, now: Instant): RoutineDto {
            if (r.ramadanOffsetMin == 0 || !AlgerianCalendar.inRamadan(now.atZone(ALGIERS).toLocalDate())) return r
            return r.copy(arriveByMin = r.arriveByMin?.plus(r.ramadanOffsetMin), dismissalMin = r.dismissalMin?.plus(r.ramadanOffsetMin))
        }

        /**
         * A place exit the routine expects (P7 false-alarm budget): leaving home in the hours before the arrive-by time
         * (on the way to school, the pasture), or leaving the routine's own place from just before dismissal on. The
         * routine rules (missed arrival, stops on the way, still after dismissal) watch these trips instead.
         */
        fun expectedExit(c: Connection, assetId: UUID, circleId: UUID, placeId: UUID, homeLike: Boolean, at: Instant): Boolean {
            val local = at.atZone(ALGIERS)
            val minute = local.hour * 60 + local.minute
            val routines = c.prepareStatement("SELECT * FROM routines WHERE asset_id = ? AND active").apply { setObject(1, assetId) }
                .executeQuery().toList { RoutineService.dto(it) }
            return routines.any { raw ->
                if (!runsOn(c, raw, circleId, local.toLocalDate())) return@any false
                val r = shifted(raw, at)
                val fromHome = homeLike && r.arriveByMin?.let { minute in (it - LEAVE_HOME_BEFORE_MIN)..(it + LEAVE_HOME_AFTER_MIN) } == true
                val fromPlace = r.placeId == placeId.toString() && r.dismissalMin?.let { minute >= it - LEAVE_EARLY_MIN } == true
                fromHome || fromPlace
            }
        }

        private const val LEAVE_HOME_BEFORE_MIN = 120
        private const val LEAVE_HOME_AFTER_MIN = 60
        private const val LEAVE_EARLY_MIN = 15
    }
}
