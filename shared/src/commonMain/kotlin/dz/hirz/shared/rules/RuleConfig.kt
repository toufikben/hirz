package dz.hirz.shared.rules

/**
 * Every rule and display threshold in one place. These are starting values to be measured,
 * not truths (design §4.3); change them only with a replay scenario that justifies it.
 */
data class RuleConfig(
    val placeMinMarginM: Int = 50,
    val exitReadings: Int = 3,
    val reentryReadings: Int = 2,
    val reentryHysteresisM: Double = 15.0,
    val boundaryMaxAgeMs: Long = 120_000,
    val boundaryAccuracyFloorM: Double = 50.0,
    val impossibleJumpKmh: Double = 300.0,
    val silentDeadMs: Long = 5 * 60_000,
    val silentAliveNoFixMs: Long = 10 * 60_000,
    val staleAfterMs: Long = 3 * 60_000,
    val silentAfterMs: Long = 5 * 60_000,
    val ackWindowP0Ms: Long = 60_000,
    val ackWindowP1Ms: Long = 180_000,
    val highRateMs: Long = 10 * 60_000,
    val ackFallbackMs: Long = 20_000,
    val escalationRetryMs: Long = 120_000,
    val nearestLeadM: Double = 300.0,
    val singleGuardianConfirmMs: Long = 120_000,
    val retentionDays: Int = 30,
    /** Vehicles (design §4.4): arm after the ignition has been off this long. */
    val autoArmAfterMs: Long = 10 * 60_000,
    /** Vehicle theft rules (design §4.3); starting values to be measured on real cars. */
    val vehicleAccuracyMaxM: Double = 50.0,
    val armedMoveM: Double = 50.0,
    val armedMovingKmh: Double = 10.0,
    val armedMovingFixes: Int = 2,
    val armedP0SpeedKmh: Double = 20.0,
    val armedP0DistanceM: Double = 500.0,
    val jammingSilenceMs: Long = 3 * 60_000,
    /** A hazard entry counts only on fixes this good: a pool or a rail line is small next to a coarse fix (P5). */
    val hazardAccuracyMaxM: Double = 25.0,
    /** Readings outside home that confirm a night exit; fewer than by day because a night outing is rarer (P5). */
    val nightExitReadings: Int = 2,
    /** How far past an excepted night destination (e.g. the mosque) still counts as on the way. */
    val nightExceptionSlackM: Double = 300.0,
    /** Running away (autism, P5): a run is faster than a brisk walk, on fixes good enough to tell. */
    val runAwayKmh: Double = 9.0,
    val runAwayFixes: Int = 2,
    val runAwayAccuracyMaxM: Double = 25.0,
    val runAwayMinStepM: Double = 15.0,
    /** Heading toward a past address (dementia, P5): fixes in a row each closer, adding up to this much closer. */
    val pastAddressHeadingFixes: Int = 4,
    val pastAddressCloserM: Double = 300.0,
    /** A step closer smaller than this is GPS noise, not walking toward the place. */
    val pastAddressMinStepM: Double = 10.0,
    /** Minutes after a teen's curfew before CURFEW fires; the check runs until 06:00. */
    val curfewGraceMin: Int = 15,
    /** A fix older than this says nothing about where the teen is now. */
    val curfewFixMaxAgeMs: Long = 30 * 60_000,
) {
    companion object {
        val DEFAULT = RuleConfig()
    }
}
