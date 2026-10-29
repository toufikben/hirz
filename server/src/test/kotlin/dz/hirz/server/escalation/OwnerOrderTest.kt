package dz.hirz.server.escalation

import dz.hirz.shared.api.LatLon
import java.util.UUID
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals

class OwnerOrderTest {
    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val c = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val child = LatLon(36.7538, 3.0588)
    private fun north(m: Double) = LatLon(child.lat + m / 111_320.0, child.lon)
    private fun east(m: Double) = LatLon(child.lat, child.lon + m / (111_320.0 * cos(Math.toRadians(child.lat))))
    private val ranks = listOf(Candidate(a, 1), Candidate(b, 2), Candidate(c, 3))

    @Test
    fun nearestNeeds300mLead() {
        assertEquals(listOf(b, a, c), OwnerOrder.rank(ranks, child, mapOf(a to north(600.0), b to east(100.0)), null, 300.0))
        assertEquals(listOf(a, b, c), OwnerOrder.rank(ranks, child, mapOf(a to north(400.0), b to east(250.0)), null, 300.0))
    }

    @Test
    fun oneKnownLocationGivesNoPreference() {
        assertEquals(listOf(a, b, c), OwnerOrder.rank(ranks, child, mapOf(c to east(10.0)), null, 300.0))
    }

    @Test
    fun custodianBeatsPriorityWhenNoClearNearest() {
        assertEquals(listOf(c, a, b), OwnerOrder.rank(ranks, child, mapOf(a to north(400.0), b to east(250.0)), c, 300.0))
    }

    @Test
    fun clearNearestBeatsCustodian() {
        assertEquals(listOf(b, c, a), OwnerOrder.rank(ranks, child, mapOf(a to north(900.0), b to east(50.0)), c, 300.0))
    }

    @Test
    fun deterministicTieBreak() {
        val tied = listOf(Candidate(c, 1), Candidate(a, 1), Candidate(b, 1))
        assertEquals(listOf(a, b, c), OwnerOrder.rank(tied, null, emptyMap(), null, 300.0))
    }
}
