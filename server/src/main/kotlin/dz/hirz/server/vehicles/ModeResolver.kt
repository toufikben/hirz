package dz.hirz.server.vehicles

import dz.hirz.server.places.Geometry
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.rules.RuleConfig
import java.time.Instant

data class ModeOverride(val mode: VehicleMode, val at: Instant)
data class AuthorizationWindow(val startsAt: Instant, val endsAt: Instant)

data class ModeInput(
    val override: ModeOverride?,
    val authorizations: List<AuthorizationWindow>,
    val schedule: List<ActiveWindow>,
    /** Last reported ignition, null when the device never reports it. */
    val ignitionOn: Boolean?,
    /** Since when the ignition has been in that state. */
    val ignitionSince: Instant?,
)

data class Resolved(val mode: VehicleMode, val reason: String, val since: Instant?)

/**
 * The vehicle's mode now (design §12.1), a pure function. Order: maintenance, an active authorization, a manual
 * switch, the armed schedule, auto-arm after the ignition has been off for [RuleConfig.autoArmAfterMs].
 * A schedule or auto-arm never arms a car whose engine is running; a manual disarm holds until the next parking.
 */
object ModeResolver {
    fun resolve(i: ModeInput, at: Instant, cfg: RuleConfig): Resolved {
        val o = i.override
        if (o?.mode == VehicleMode.MAINTENANCE) return Resolved(VehicleMode.MAINTENANCE, "manual", o.at)
        i.authorizations.firstOrNull { !at.isBefore(it.startsAt) && at.isBefore(it.endsAt) }
            ?.let { return Resolved(VehicleMode.AUTHORIZED, "authorized", it.startsAt) }

        val parkedSince = i.ignitionSince?.takeIf { i.ignitionOn == false }
        val autoArmAt = parkedSince?.plusMillis(cfg.autoArmAfterMs)
        val autoArm = autoArmAt != null && !at.isBefore(autoArmAt)
        val lastAuthEnd = i.authorizations.map { it.endsAt }.filter { !it.isAfter(at) }.maxOrNull()

        if (o?.mode == VehicleMode.ARMED && (lastAuthEnd == null || lastAuthEnd.isBefore(o.at))) return Resolved(VehicleMode.ARMED, "manual", o.at)
        if (o?.mode == VehicleMode.DISARMED && (lastAuthEnd == null || lastAuthEnd.isBefore(o.at))) {
            // A parking that started after the disarm re-arms the car; one that started before does not.
            return if (autoArm && parkedSince!!.isAfter(o.at)) Resolved(VehicleMode.ARMED, "auto_arm", autoArmAt)
            else Resolved(VehicleMode.DISARMED, "manual", o.at)
        }
        if (i.schedule.isNotEmpty() && i.ignitionOn != true && Geometry.isActive(i.schedule, at)) return Resolved(VehicleMode.ARMED, "schedule", null)
        // Parked through the end of an authorization: the auto-arm delay counts from the end, not from the parking.
        val armFrom = parkedSince?.let { p -> lastAuthEnd?.takeIf { it.isAfter(p) } ?: p }?.plusMillis(cfg.autoArmAfterMs)
        if (armFrom != null && !at.isBefore(armFrom)) return Resolved(VehicleMode.ARMED, "auto_arm", armFrom)
        if (lastAuthEnd != null) return Resolved(VehicleMode.DISARMED, "expired", lastAuthEnd)
        return Resolved(VehicleMode.DISARMED, "default", null)
    }
}
