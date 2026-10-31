package dz.hirz.server.buses

import dz.hirz.server.rules.BusFix
import dz.hirz.server.rules.BusRules
import dz.hirz.shared.api.BusStopDto
import dz.hirz.shared.api.LatLon
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val A = LatLon(36.7400, 3.0500)
private val B = LatLon(36.7538, 3.0500) // ≈ 1.5 km north
private val PATH = listOf(A, B)
private val T0 = Instant.parse("2026-11-08T06:00:00Z")
private fun east(m: Double, north: Double = 700.0) = LatLon(A.lat + north / 111_320.0, A.lon + m / (111_320.0 * 0.8017))
private fun fixes(n: Int, everyS: Long, at: (Int) -> LatLon, speed: Double? = null) = (0 until n).map { BusFix(T0.plusSeconds(it * everyS), at(it), 10.0, speed) }

class BusRulesTest {
    @Test
    fun distanceToThePath() {
        assertEquals(200.0, BusRules.distanceToPathM(east(200.0), PATH), 2.0)
        assertEquals(0.0, BusRules.distanceToPathM(east(0.0), PATH), 1.0)
    }

    @Test
    fun offCorridorForAMinuteIsADeviation() {
        assertTrue(BusRules.offCorridor(fixes(5, 20, { east(300.0) }), PATH, 80.0), "300 m off for 80 s")
        assertFalse(BusRules.offCorridor(fixes(2, 20, { east(300.0) }), PATH, 80.0), "20 s is a detour round a lorry")
    }

    @Test
    fun gpsJitterOnTheCorridorIsNot() {
        assertFalse(BusRules.offCorridor(fixes(10, 20, { i -> east(if (i % 2 == 0) 45.0 else -45.0) }), PATH, 80.0), "45 m with ±10 m accuracy is on an 80 m corridor")
    }

    @Test
    fun aRedLightIsNotAnUnscheduledStop() {
        assertNull(BusRules.stillOffStops(fixes(5, 30, { east(0.0) }), emptyList()), "2 min at a light")
        assertNotNull(BusRules.stillOffStops(fixes(12, 30, { east(0.0) }), emptyList()), "5.5 min still")
        val stop = BusStopDto(0, "Cité", east(0.0), 50.0, 420)
        assertNull(BusRules.stillOffStops(fixes(12, 30, { east(0.0) }), listOf(stop)), "waiting at a stop is the job")
    }

    @Test
    fun overspeedForAMinute() {
        assertTrue(BusRules.overspeed(fixes(5, 20, { east(0.0, 100.0 + it * 500) }, speed = 95.0)))
        assertFalse(BusRules.overspeed(fixes(5, 20, { east(0.0) }, speed = 80.0)))
    }

    @Test
    fun lateAtAStop() {
        val stops = listOf(BusStopDto(0, "Cité", A, 50.0, 7 * 60), BusStopDto(1, "Ecole", B, 50.0, 7 * 60 + 40))
        assertNull(BusRules.lateStop(stops, emptySet(), 7 * 60 + 5), "5 min is traffic")
        assertEquals("Cité", BusRules.lateStop(stops, emptySet(), 7 * 60 + 11)?.name)
        assertNull(BusRules.lateStop(stops, setOf(0), 7 * 60 + 11), "already there")
    }
}
