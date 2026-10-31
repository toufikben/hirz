package dz.hirz.app.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayLogicTest {
    private val ewe = "eddystone:0102030405060708090a0b0c0d0e0f10"
    private val ram = "ibeacon:fda50693-a4e2-4fb1-afcf-c6eb07647825:1:42"

    @Test
    fun onlyRegisteredTagsLeaveThePhone() {
        val buf = RelayBuffer(setOf(ewe))
        assertTrue(buf.offer(ewe, -60, 1_000))
        assertFalse(buf.offer(ram, -60, 1_000), "a stranger's tag never leaves the phone")
        assertEquals(listOf(ewe), buf.nextBatch().map { it.tagId })
    }

    @Test
    fun throttlePerTag() {
        val buf = RelayBuffer(setOf(ewe, ram))
        assertTrue(buf.offer(ewe, -60, 0))
        assertFalse(buf.offer(ewe, -58, 10_000), "the same tag within 30 s")
        assertTrue(buf.offer(ram, -70, 10_000), "another tag is its own clock")
        assertTrue(buf.offer(ewe, -61, 31_000))
        assertEquals(3, buf.size)
    }

    @Test
    fun offlineSightingsQueueAndFlushInOrder() {
        val buf = RelayBuffer(setOf(ewe, ram), capacity = 3)
        listOf(0L, 40_000L, 80_000L, 120_000L).forEach { buf.offer(ewe, -60, it) }
        assertEquals(listOf(40_000L, 80_000L, 120_000L), buf.nextBatch().map { it.seenAtMs }, "full: the oldest goes first")
        buf.acknowledge(2)
        assertEquals(listOf(120_000L), buf.nextBatch().map { it.seenAtMs })
    }

    @Test
    fun dutyCycleSavesBattery() {
        val scanning = (0 until 60).count { s -> RelayDuty.scanning(s * 1_000L) }
        assertEquals(10, scanning, "10 s of every 60")
        assertTrue(RelayDuty.scanning(120_005L))
        assertFalse(RelayDuty.scanning(130_000L))
    }
}
