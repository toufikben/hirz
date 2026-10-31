package dz.hirz.shared.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a trial measured, over all its enrolled asset-days (P8). An asset-day is one asset with a live source for at
 * least 12 h of that day. Nulls mean nothing was measured.
 */
@Serializable
data class TrialMetrics(
    val assetDays: Int,
    val falseP0: Int,
    val falseP1: Int,
    /** Guided tests that should have rung, and how many did not. */
    val guidedTests: Int,
    val guidedMissed: Int,
    val p0GuidedTests: Int,
    val p0DeliveryP95Ms: Long?,
    val p0AckMedianMs: Long?,
    /** Share of asset-hours with a fresh fix, 0..1. */
    val coverage: Double?,
    val digestRated: Int,
    val digestUseful: Int,
    val closedP0P1: Int,
    val reviewedP0P1: Int,
)

@Serializable
enum class Verdict { @SerialName("pass") PASS, @SerialName("fail") FAIL, @SerialName("not_enough_data") NOT_ENOUGH_DATA }

/** One budget line's result: [actual] and [limit] are human-readable, for the report and the CSV. */
@Serializable
data class BudgetLine(val id: String, val verdict: Verdict, val actual: String, val limit: String)

/**
 * The false-alarm budget (P8, design §1 "a measured, published budget"). These numbers are the gate; the published
 * table in `docs/research/false-alarm-budget.md` is checked against them in CI.
 */
object FalseAlarmBudget {
    /** At most one false P0 per this many asset-days. */
    const val FALSE_P0_PER_ASSET_DAYS = 300
    /** At most one false P1 per this many asset-days (about one a month per asset). */
    const val FALSE_P1_PER_ASSET_DAYS = 30
    const val P0_DELIVERY_P95_MS = 30_000L
    const val P0_ACK_MEDIAN_MS = 180_000L
    const val COVERAGE_MIN = 0.90
    const val DIGEST_USEFUL_MIN = 0.50
    const val OUTCOME_COVERAGE_MIN = 0.80
    /** Below these, a line is "not enough data", which never passes. */
    const val MIN_ASSET_DAYS = 100
    const val MIN_P0_GUIDED_TESTS = 5

    /** The lines, in report order, with their published limits. */
    val LINES = listOf(
        "false_p0" to "≤ 1 per $FALSE_P0_PER_ASSET_DAYS asset-days",
        "false_p1" to "≤ 1 per $FALSE_P1_PER_ASSET_DAYS asset-days",
        "missed_real" to "0 missed guided tests",
        "p0_delivery" to "p95 ≤ ${P0_DELIVERY_P95_MS / 1000} s",
        "p0_ack" to "median ≤ ${P0_ACK_MEDIAN_MS / 60_000} min",
        "coverage" to "≥ ${(COVERAGE_MIN * 100).toInt()}% of asset-hours",
        "digest_useful" to "≥ ${(DIGEST_USEFUL_MIN * 100).toInt()}% of rated lines",
        "outcome_coverage" to "≥ ${(OUTCOME_COVERAGE_MIN * 100).toInt()}% of closed P0/P1",
    )
}

/** Judges a trial against [FalseAlarmBudget]. Pure, so the CI test, the report and the doc share it. */
object BudgetGate {
    private fun pct(x: Double) = "${(x * 1000).toInt() / 10.0}%"

    fun judge(m: TrialMetrics): List<BudgetLine> {
        val b = FalseAlarmBudget
        val limits = b.LINES.toMap()
        val enoughDays = m.assetDays >= b.MIN_ASSET_DAYS
        fun line(id: String, verdict: Verdict, actual: String) = BudgetLine(id, verdict, actual, limits.getValue(id))
        fun rate(count: Int, per: Int) = when {
            !enoughDays -> Verdict.NOT_ENOUGH_DATA
            count.toLong() * per <= m.assetDays -> Verdict.PASS
            else -> Verdict.FAIL
        }
        fun share(part: Int, whole: Int, min: Double): Pair<Verdict, String> =
            if (whole <= 0) Verdict.NOT_ENOUGH_DATA to "no data"
            else (if (part.toDouble() / whole >= min) Verdict.PASS else Verdict.FAIL) to "${pct(part.toDouble() / whole)} ($part/$whole)"
        return listOf(
            line("false_p0", rate(m.falseP0, b.FALSE_P0_PER_ASSET_DAYS), "${m.falseP0} in ${m.assetDays} asset-days"),
            line("false_p1", rate(m.falseP1, b.FALSE_P1_PER_ASSET_DAYS), "${m.falseP1} in ${m.assetDays} asset-days"),
            line("missed_real", when {
                m.p0GuidedTests < b.MIN_P0_GUIDED_TESTS -> Verdict.NOT_ENOUGH_DATA
                m.guidedMissed == 0 -> Verdict.PASS
                else -> Verdict.FAIL
            }, "${m.guidedMissed} of ${m.guidedTests} (P0 tests: ${m.p0GuidedTests})"),
            line("p0_delivery", m.p0DeliveryP95Ms?.let { if (it <= b.P0_DELIVERY_P95_MS) Verdict.PASS else Verdict.FAIL } ?: Verdict.NOT_ENOUGH_DATA,
                m.p0DeliveryP95Ms?.let { "${it / 1000.0} s" } ?: "no data"),
            line("p0_ack", m.p0AckMedianMs?.let { if (it <= b.P0_ACK_MEDIAN_MS) Verdict.PASS else Verdict.FAIL } ?: Verdict.NOT_ENOUGH_DATA,
                m.p0AckMedianMs?.let { "${it / 1000} s" } ?: "no data"),
            line("coverage", m.coverage?.let { if (it >= b.COVERAGE_MIN) Verdict.PASS else Verdict.FAIL } ?: Verdict.NOT_ENOUGH_DATA,
                m.coverage?.let { pct(it) } ?: "no data"),
            share(m.digestUseful, m.digestRated, b.DIGEST_USEFUL_MIN).let { (v, a) -> line("digest_useful", v, a) },
            share(m.reviewedP0P1, m.closedP0P1, b.OUTCOME_COVERAGE_MIN).let { (v, a) -> line("outcome_coverage", v, a) },
        )
    }

    /** Only every line passing passes; "not enough data" is a fail. */
    fun passed(lines: List<BudgetLine>): Boolean = lines.isNotEmpty() && lines.all { it.verdict == Verdict.PASS }
}
