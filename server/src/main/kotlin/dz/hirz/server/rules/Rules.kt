package dz.hirz.server.rules

import dz.hirz.shared.api.CheckInReply
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.Reason
import dz.hirz.shared.rules.RuleConfig
import dz.hirz.shared.signal.AlarmKind
import kotlin.math.roundToInt

enum class BoundaryStatus { UNKNOWN, INSIDE, OUTSIDE }

data class PlaceState(
    val status: BoundaryStatus = BoundaryStatus.UNKNOWN,
    val outsideCount: Int = 0,
    val insideCount: Int = 0,
    val lastFixMs: Long? = null,
    /** The first reading of the current run beyond the margin: when the trip began (P7). */
    val firstOutMs: Long? = null,
)

/** One accepted boundary reading of an asset against one place. */
data class Reading(val fixMs: Long, val distanceOutsideM: Double, val accuracyM: Double, val ageMs: Long)

data class StepResult(val state: PlaceState, val reason: Reason?)

/** Places whose *entry* is the event (design §4.3 FORBIDDEN_PLACE / hazard). */
fun PlaceKind.alarmsOnEntry(): Boolean = this == PlaceKind.FORBIDDEN || this == PlaceKind.HAZARD || this == PlaceKind.COAST

/**
 * Pure boundary state machine (design §4.2). For ordinary places, [RuleConfig.exitReadings] consecutive readings
 * beyond the margin confirm an exit; re-entry needs [RuleConfig.reentryReadings] readings at least
 * [RuleConfig.reentryHysteresisM] inside. A first known state of "outside" never fires. Exits outside the
 * place's active hours update the state silently. Forbidden/hazard places mirror this and fire on entry.
 */
object PlaceBoundary {
    fun accepts(state: PlaceState, place: PlaceDto, r: Reading, cfg: RuleConfig): Boolean =
        r.ageMs <= cfg.boundaryMaxAgeMs &&
            r.accuracyM <= maxOf(cfg.boundaryAccuracyFloorM, place.marginM.toDouble()) &&
            (state.lastFixMs == null || r.fixMs > state.lastFixMs)

    /** [entryReadings]: readings inside before an entry counts (the profile's, e.g. 1 at a hazard for autism). */
    fun step(
        state: PlaceState, place: PlaceDto, r: Reading, active: Boolean, cfg: RuleConfig,
        entryReadings: Int = cfg.exitReadings, exitReadings: Int = cfg.exitReadings,
    ): StepResult {
        val s = state.copy(lastFixMs = r.fixMs)
        // A coarse fix near a hazard neither counts toward an entry nor resets one in progress.
        if (place.kind == PlaceKind.HAZARD && r.accuracyM > cfg.hazardAccuracyMaxM) return StepResult(s, null)
        return if (place.kind.alarmsOnEntry()) entryStep(s, place, r, active, cfg, entryReadings) else exitStep(s, place, r, active, cfg, exitReadings)
    }

    private fun exitStep(s: PlaceState, place: PlaceDto, r: Reading, active: Boolean, cfg: RuleConfig, exitReadings: Int): StepResult {
        val beyond = r.distanceOutsideM > place.marginM
        val deep = r.distanceOutsideM <= -cfg.reentryHysteresisM
        return when (s.status) {
            BoundaryStatus.INSIDE -> {
                if (!beyond) return StepResult(s.copy(outsideCount = 0, insideCount = 0, firstOutMs = null), null)
                val count = s.outsideCount + 1
                val began = if (s.outsideCount == 0) r.fixMs else s.firstOutMs ?: r.fixMs
                if (count < exitReadings) return StepResult(s.copy(outsideCount = count, firstOutMs = began), null)
                StepResult(PlaceState(BoundaryStatus.OUTSIDE, 0, 0, s.lastFixMs), if (active) reason("left_place", place, r, count) else null)
            }
            BoundaryStatus.OUTSIDE, BoundaryStatus.UNKNOWN -> {
                if (deep) {
                    val count = s.insideCount + 1
                    if (count >= cfg.reentryReadings) StepResult(PlaceState(BoundaryStatus.INSIDE, 0, 0, s.lastFixMs), null)
                    else StepResult(s.copy(insideCount = count), null)
                } else {
                    val status = if (s.status == BoundaryStatus.UNKNOWN && beyond) BoundaryStatus.OUTSIDE else s.status
                    StepResult(s.copy(status = status, insideCount = 0), null)
                }
            }
        }
    }

    private fun entryStep(s: PlaceState, place: PlaceDto, r: Reading, active: Boolean, cfg: RuleConfig, entryReadings: Int): StepResult {
        val inside = r.distanceOutsideM <= 0.0
        val clearlyOut = r.distanceOutsideM > cfg.reentryHysteresisM
        return when (s.status) {
            BoundaryStatus.INSIDE -> {
                if (!clearlyOut) return StepResult(s.copy(outsideCount = 0), null)
                val count = s.outsideCount + 1
                if (count >= cfg.reentryReadings) StepResult(PlaceState(BoundaryStatus.OUTSIDE, 0, 0, s.lastFixMs), null)
                else StepResult(s.copy(outsideCount = count), null)
            }
            BoundaryStatus.OUTSIDE, BoundaryStatus.UNKNOWN -> {
                if (!inside) return StepResult(s.copy(status = BoundaryStatus.OUTSIDE, insideCount = 0), null)
                val count = s.insideCount + 1
                if (count < entryReadings) return StepResult(s.copy(insideCount = count), null)
                val code = when (place.kind) { PlaceKind.HAZARD -> "hazard_entered"; PlaceKind.COAST -> "coastal_night"; else -> "entered_place" }
                StepResult(PlaceState(BoundaryStatus.INSIDE, 0, 0, s.lastFixMs), if (active) reason(code, place, r, count) else null)
            }
        }
    }

    private fun reason(code: String, place: PlaceDto, r: Reading, readings: Int) = Reason(
        code,
        mapOf(
            "place" to place.name,
            "readings" to readings.toString(),
            "distanceM" to r.distanceOutsideM.roundToInt().toString(),
            "accuracyM" to r.accuracyM.roundToInt().toString(),
            "ageS" to (r.ageMs / 1000).toString(),
        ),
    )
}

/** SOS, a "help" check-in reply and duress (design §4.3). Duress findings are silent toward the carrier. */
object Alarms {
    data class Hit(val duress: Boolean, val reason: Reason)

    fun evaluate(alarms: Set<AlarmKind>, reply: CheckInReply?): List<Hit> = buildList {
        if (AlarmKind.SOS in alarms) add(Hit(false, Reason("sos")))
        else if (reply == CheckInReply.HELP) add(Hit(false, Reason("checkin_help")))
        if (AlarmKind.DURESS in alarms) add(Hit(true, Reason("duress")))
    }
}
