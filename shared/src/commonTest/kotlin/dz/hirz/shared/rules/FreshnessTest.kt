package dz.hirz.shared.rules

import dz.hirz.shared.api.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FreshnessTest {
    @Test
    fun freshnessBoundaries() {
        assertEquals(Freshness.FRESH, freshnessOf(0))
        assertEquals(Freshness.FRESH, freshnessOf(179_999))
        assertEquals(Freshness.STALE, freshnessOf(180_000))
        assertEquals(Freshness.STALE, freshnessOf(299_999))
        assertEquals(Freshness.SILENT, freshnessOf(300_000))
        assertEquals(Freshness.SILENT, freshnessOf(null))
    }

    @Test
    fun defaultsMatchTheDesign() {
        val c = RuleConfig.DEFAULT
        assertEquals(50, c.placeMinMarginM)
        assertEquals(3, c.exitReadings)
        assertEquals(2, c.reentryReadings)
        assertEquals(120_000L, c.boundaryMaxAgeMs)
        assertEquals(300.0, c.impossibleJumpKmh)
        assertEquals(30, c.retentionDays)
    }

    @Test
    fun latLonIsValidated() {
        assertFailsWith<IllegalArgumentException> { LatLon(-91.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { LatLon(0.0, 180.5) }
    }
}
