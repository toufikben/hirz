package dz.hirz.server.vehicles

import dz.hirz.server.places.Geometry
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.rules.RuleConfig
import java.time.Instant

data class ModeOverride(val mode: VehicleMode, val at: Instant)
data class AuthorizationWindow(val startsAt: Instant, val endsAt: Instant)

/** The stored mode when the schedule or auto-arm armed the car ([reason] auto_arm or schedule, since [since]). */
data class Latched(val reason: String, val since: Instant)

data class ModeInput(
    val override: ModeOverride?,
    val authorizations: List<AuthorizationWindow>,
    val schedule: List<ActiveWindow>,
    /** Last reported ignition, null when the device never reports it. */
    val ignitionOn: Boolean?,
    /** Since when the ignition has been in that state. */
    val ignitionSince: Instant?,
    /** Set while the car is armed by auto-arm or the schedule: that arming outlives the ignition turning on. */
    val latched: Latched? = null,
    /** When a family phone last heard the car's BLE tag; null when the car has no tag or none ever did (P6). */
    val ownerSeenAt: Instant? = null,
    /** A bus inside one of its runs (P9). */
    val inService: Boolean = false,
)

data class Resolved(val mode: VehicleMode, val reason: String, val since: Instant?)

/**
 * The vehicle's mode now (design §12.1), a pure function. Order: maintenance, an active authorization, a bus's run (P9), a manual
 * switch, the armed schedule, auto-arm after the ignition has been off for [RuleConfig.autoArmAfterMs].
 * A schedule or auto-arm never arms a car whose engine is running; a manual disarm holds until the next parking.
 * Once they have armed it, the arming holds when the engine starts (a thief turning the key must not disarm the car):
 * only an authorization, the owner's own switch or maintenance ends it. A schedule arming also ends at the end of its
 * window if the car is still parked then.
 */
object ModeResolver {
    fun resolve(i: ModeInput, at: Instant, cfg: RuleConfig): Resolved {
        val o = i.override
        if (o?.mode == VehicleMode.MAINTENANCE) return Resolved(VehicleMode.MAINTENANCE, "manual", o.at)
        i.authorizations.firstOrNull { !at.isBefore(it.startsAt) && at.isBefore(it.endsAt) }
            ?.let { return Resolved(VehicleMode.AUTHORIZED, "authorized", it.startsAt) }
        // A bus on its run: after maintenance and a drive authorization, before any arming.
        if (i.inService) return Resolved(VehicleMode.ROUTE, "service", null)

        val parkedSince = i.ignitionSince?.takeIf { i.ignitionOn == false }
        val autoArmAt = parkedSince?.plusMillis(cfg.autoArmAfterMs)
        val autoArm = autoArmAt != null && !at.isBefore(autoArmAt)
        val lastAuthEnd = i.authorizations.map { it.endsAt }.filter { !it.isAfter(at) }.maxOrNull()
        // Owner away (P6): parked, and no family phone has heard the car's tag for ownerAwayMs.
        val awayFrom = if (parkedSince != null && i.ownerSeenAt != null) maxOf(parkedSince, i.ownerSeenAt).plusMillis(cfg.ownerAwayMs) else null
        val awayArm = awayFrom != null && !at.isBefore(awayFrom) && (lastAuthEnd == null || lastAuthEnd.isBefore(awayFrom))

        val l = i.latched
        if (l != null && (o == null || o.at.isBefore(l.since)) && (lastAuthEnd == null || lastAuthEnd.isBefore(l.since))) {
            if (l.reason == "auto_arm") return Resolved(VehicleMode.ARMED, l.reason, l.since)
            // The owner's phone heard the car again after it armed: they came back, the latch lets go.
            val returned = i.ownerSeenAt != null && i.ownerSeenAt.isAfter(l.since)
            if (l.reason == "owner_away" && !returned) return Resolved(VehicleMode.ARMED, l.reason, l.since)
            if (l.reason == "schedule" && (i.ignitionOn == true || Geometry.isActive(i.schedule, at))) return Resolved(VehicleMode.ARMED, "schedule", l.since)
        }

        if (o?.mode == VehicleMode.ARMED && (lastAuthEnd == null || lastAuthEnd.isBefore(o.at))) return Resolved(VehicleMode.ARMED, "manual", o.at)
        if (o?.mode == VehicleMode.DISARMED && (lastAuthEnd == null || lastAuthEnd.isBefore(o.at))) {
            // A parking that started after the disarm re-arms the car; one that started before does not.
            return when {
                awayArm && parkedSince!!.isAfter(o.at) -> Resolved(VehicleMode.ARMED, "owner_away", awayFrom)
                autoArm && parkedSince!!.isAfter(o.at) -> Resolved(VehicleMode.ARMED, "auto_arm", autoArmAt)
                else -> Resolved(VehicleMode.DISARMED, "manual", o.at)
            }
        }
        if (i.schedule.isNotEmpty() && i.ignitionOn != true && Geometry.isActive(i.schedule, at)) return Resolved(VehicleMode.ARMED, "schedule", null)
        if (awayArm) return Resolved(VehicleMode.ARMED, "owner_away", awayFrom)
        // Parked through the end of an authorization: the auto-arm delay counts from the end, not from the parking.
        val armFrom = parkedSince?.let { p -> lastAuthEnd?.takeIf { it.isAfter(p) } ?: p }?.plusMillis(cfg.autoArmAfterMs)
        if (armFrom != null && !at.isBefore(armFrom)) return Resolved(VehicleMode.ARMED, "auto_arm", armFrom)
        if (lastAuthEnd != null) return Resolved(VehicleMode.DISARMED, "expired", lastAuthEnd)
        return Resolved(VehicleMode.DISARMED, "default", null)
    }
}
