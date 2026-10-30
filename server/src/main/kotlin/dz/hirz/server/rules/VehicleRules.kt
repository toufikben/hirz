package dz.hirz.server.rules

import dz.hirz.server.escalation.OwnerOrder
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.rules.RuleConfig
import dz.hirz.shared.signal.AlarmKind
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

data class VehicleReading(
    val fixMs: Long?,
    val position: LatLon?,
    val accuracyM: Double?,
    val speedKmh: Double?,
    val ignition: Boolean?,
    val alarms: Set<AlarmKind>,
    val isLatestFix: Boolean,
)

/** Consecutive moving fixes, persisted per asset (`vehicle_rule_state`). */
data class VehicleRuleState(val movingCount: Int = 0, val lastFixMs: Long? = null)

data class VehicleHit(val rule: RuleId, val tier: Tier, val reason: Reason)

/**
 * Theft rules for vehicles (design §4.3, §12.2), a pure function. Movement rules run only while ARMED and measure from
 * the armed position, net of the fix's accuracy, so GPS drift under a building is not theft. Power cut, jamming and
 * antenna cut always count, at P1 when armed and P2 otherwise.
 */
object VehicleRules {
    fun evaluate(st: VehicleRuleState, mode: VehicleMode, armedAt: LatLon?, r: VehicleReading, cfg: RuleConfig): Pair<VehicleRuleState, List<VehicleHit>> {
        val armed = mode == VehicleMode.ARMED
        val hits = mutableListOf<VehicleHit>()
        val always = if (armed) Tier.P1 else Tier.P2
        if (AlarmKind.POWER_CUT in r.alarms) hits += VehicleHit(RuleId.POWER_CUT, always, Reason("power_cut"))
        if (AlarmKind.JAMMING in r.alarms) hits += VehicleHit(RuleId.JAMMING, always, Reason("jamming_alarm"))
        if (AlarmKind.ANTENNA_CUT in r.alarms) hits += VehicleHit(RuleId.ANTENNA_CUT, always, Reason("antenna_cut"))

        var next = st
        val fresh = r.position != null && r.fixMs != null && r.isLatestFix && (st.lastFixMs == null || r.fixMs > st.lastFixMs)
        val goodFix = fresh && (r.accuracyM ?: Double.MAX_VALUE) <= cfg.vehicleAccuracyMaxM
        if (fresh) next = next.copy(lastFixMs = r.fixMs, movingCount = if ((r.speedKmh ?: 0.0) >= cfg.armedMovingKmh) st.movingCount + 1 else 0)
        if (!armed) return next to hits

        if (AlarmKind.DOOR in r.alarms) hits += VehicleHit(RuleId.DOOR_OPEN_ARMED, Tier.P1, Reason("door_open_armed"))
        // Distance beyond the fix's own uncertainty: a 80 m drift with ±45 m accuracy is 35 m of evidence.
        val netM = if (goodFix && armedAt != null) OwnerOrder.metres(armedAt, r.position!!) - (r.accuracyM ?: 0.0) else 0.0
        val grossM = if (goodFix && armedAt != null) OwnerOrder.metres(armedAt, r.position!!) else 0.0
        val moved = netM >= cfg.armedMoveM || next.movingCount >= cfg.armedMovingFixes
        val speed = r.speedKmh ?: 0.0
        val tier = if (speed >= cfg.armedP0SpeedKmh && (moved || r.ignition == true) || grossM >= cfg.armedP0DistanceM) Tier.P0 else Tier.P1
        val args = mapOf("distanceM" to grossM.roundToInt().toString(), "speedKmh" to speed.roundToInt().toString())
        when {
            moved && r.ignition == false -> hits += VehicleHit(RuleId.TOWING, Tier.P1, Reason("towed", args))
            moved -> hits += VehicleHit(RuleId.ARMED_MOVEMENT, tier, Reason("armed_moved", args))
            r.ignition == true -> hits += VehicleHit(RuleId.ARMED_MOVEMENT, tier, Reason("armed_ignition_on", args))
            AlarmKind.VIBRATION in r.alarms -> hits += VehicleHit(RuleId.PARKED_TILT, Tier.P1, Reason("parked_tilt"))
        }
        return next to hits
    }

    /**
     * Jamming by silence: the last report had the engine running and nothing has arrived since [RuleConfig.jammingSilenceMs].
     * A parked car going quiet is the silence rule's job; heartbeats without fixes (a garage) are not silence.
     */
    fun jamming(lastReportAt: Instant?, ignitionOn: Boolean?, alreadyFlagged: Boolean, mode: VehicleMode, now: Instant, cfg: RuleConfig): VehicleHit? {
        if (ignitionOn != true || alreadyFlagged || lastReportAt == null) return null
        val silent = Duration.between(lastReportAt, now)
        if (silent.toMillis() < cfg.jammingSilenceMs) return null
        return VehicleHit(RuleId.JAMMING, if (mode == VehicleMode.ARMED) Tier.P1 else Tier.P2, Reason("jamming", mapOf("silentS" to silent.seconds.toString())))
    }
}
