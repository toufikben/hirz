package dz.hirz.shared.rules

import dz.hirz.shared.api.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BudgetGateTest {
    /** A good month: 300 asset-days, one false P1 in ten, every guided test rang. */
    private val good = TrialMetrics(
        assetDays = 300, falseP0 = 0, falseP1 = 5, guidedTests = 40, guidedMissed = 0, p0GuidedTests = 10,
        p0DeliveryP95Ms = 12_000, p0AckMedianMs = 90_000, coverage = 0.95, digestRated = 60, digestUseful = 40,
        closedP0P1 = 50, reviewedP0P1 = 45, p0Alarms = 10,
    )

    private fun verdict(m: TrialMetrics, id: String) = BudgetGate.judge(m).single { it.id == id }.verdict

    @Test
    fun withinBudgetPasses() {
        val lines = BudgetGate.judge(good)
        assertEquals(FalseAlarmBudget.LINES.size, lines.size)
        assertTrue(BudgetGate.passed(lines), lines.filter { it.verdict != Verdict.PASS }.toString())
    }

    @Test
    fun oneFalseP0TooManyFails() {
        assertEquals(Verdict.PASS, verdict(good.copy(falseP0 = 1), "false_p0"), "1 in 300 asset-days is the limit")
        assertEquals(Verdict.FAIL, verdict(good.copy(falseP0 = 2), "false_p0"))
        assertEquals(Verdict.FAIL, verdict(good.copy(falseP1 = 11), "false_p1"))
        assertFalse(BudgetGate.passed(BudgetGate.judge(good.copy(falseP0 = 2))))
    }

    @Test
    fun missedGuidedTestFails() {
        assertEquals(Verdict.FAIL, verdict(good.copy(guidedMissed = 1), "missed_real"), "a real event that did not ring")
    }

    @Test
    fun notEnoughDataIsAFail() {
        val thin = good.copy(assetDays = 99)
        assertEquals(Verdict.NOT_ENOUGH_DATA, verdict(thin, "false_p0"))
        assertEquals(Verdict.NOT_ENOUGH_DATA, verdict(good.copy(p0GuidedTests = 4), "missed_real"))
        assertEquals(Verdict.NOT_ENOUGH_DATA, verdict(good.copy(p0DeliveryP95Ms = null), "p0_delivery"))
        assertEquals(Verdict.NOT_ENOUGH_DATA, verdict(good.copy(closedP0P1 = 0, reviewedP0P1 = 0), "outcome_coverage"))
        assertEquals(Verdict.NOT_ENOUGH_DATA, verdict(good.copy(p0Alarms = 1), "p0_delivery"), "one P0 says nothing about p95")
        assertEquals(Verdict.NOT_ENOUGH_DATA, verdict(good.copy(digestRated = 3, digestUseful = 3), "digest_useful"))
        assertEquals(Verdict.NOT_ENOUGH_DATA, verdict(thin, "coverage"))
        assertFalse(BudgetGate.passed(BudgetGate.judge(thin)), "not enough data never passes")
    }

    @Test
    fun otherLinesJudged() {
        assertEquals(Verdict.FAIL, verdict(good.copy(p0DeliveryP95Ms = 31_000), "p0_delivery"))
        assertEquals(Verdict.FAIL, verdict(good.copy(p0DeliveryP95Ms = Long.MAX_VALUE), "p0_delivery"), "a P0 that never arrived")
        assertEquals(Verdict.FAIL, verdict(good.copy(p0AckMedianMs = 181_000), "p0_ack"))
        assertEquals(Verdict.FAIL, verdict(good.copy(coverage = 0.89), "coverage"))
        assertEquals(Verdict.FAIL, verdict(good.copy(digestUseful = 29), "digest_useful"))
        assertEquals(Verdict.FAIL, verdict(good.copy(reviewedP0P1 = 39), "outcome_coverage"))
    }

    @Test
    fun testOutcomesNeverCountAsFalse() {
        assertFalse(Outcome.TEST.isFalseAlarm)
        assertFalse(Outcome.REAL.isFalseAlarm)
        assertFalse(Outcome.UNSURE.isFalseAlarm)
        assertTrue(Outcome.FALSE_DEVICE.isFalseAlarm && Outcome.FALSE_RULE.isFalseAlarm)
    }
}
