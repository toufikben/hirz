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
) {
    companion object {
        val DEFAULT = RuleConfig()
    }
}
