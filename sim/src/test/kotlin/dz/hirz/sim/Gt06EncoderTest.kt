package dz.hirz.sim

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }

class Gt06EncoderTest {
    private val now = Instant.parse("2026-10-27T13:00:00Z")

    private fun courseStatus(packet: ByteArray): Int =
        ((packet[20].toInt() and 0xFF) shl 8) or (packet[21].toInt() and 0xFF)

    private fun embeddedCrc(packet: ByteArray): Int =
        ((packet[packet.size - 4].toInt() and 0xFF) shl 8) or (packet[packet.size - 3].toInt() and 0xFF)

    @Test
    fun loginMatchesProtocolSpecExample() {
        assertEquals(
            "78 78 0D 01 01 23 45 67 89 01 23 45 00 01 8C DD 0D 0A",
            Gt06Encoder.login("0123456789012345", 1).hex(),
        )
    }

    @Test
    fun encodesAlgiersCoordinates() {
        assertEquals(66_156_840L, Gt06Encoder.encodeCoordinate(36.7538))
        assertEquals(5_505_840L, Gt06Encoder.encodeCoordinate(3.0588))
    }

    @Test
    fun locationPacketHasValidFrameAndCrc() {
        val packet = Gt06Encoder.location(Gt06Fix(36.7538, 3.0588, 30, 90, now, 8), serial = 7)
        assertEquals(0x78, packet[0].toInt() and 0xFF)
        assertEquals(0x78, packet[1].toInt() and 0xFF)
        assertEquals(0x1F, packet[2].toInt())
        assertEquals(0x12, packet[3].toInt())
        assertEquals(36, packet.size)
        assertEquals(0x0D, packet[packet.size - 2].toInt())
        assertEquals(0x0A, packet[packet.size - 1].toInt())
        assertEquals(Crc16X25.compute(packet, 2, packet.size - 4), embeddedCrc(packet))
        assertEquals(7, ((packet[packet.size - 6].toInt() and 0xFF) shl 8) or (packet[packet.size - 5].toInt() and 0xFF))
    }

    @Test
    fun encodesDateTimeInUtc() {
        val packet = Gt06Encoder.location(Gt06Fix(36.7538, 3.0588, 0, 0, now, 8), serial = 1)
        assertEquals("1A 0A 1B 0D 00 00", packet.copyOfRange(4, 10).hex())
    }

    @Test
    fun encodesSouthWestHemisphereBits() {
        val southWest = courseStatus(Gt06Encoder.location(Gt06Fix(-33.9, -18.4, 0, 0, now, 8), 1))
        assertEquals(0, southWest shr 10 and 1, "north bit must be 0 in the south")
        assertEquals(1, southWest shr 11 and 1, "west bit must be 1 in the west")
        val algiers = courseStatus(Gt06Encoder.location(Gt06Fix(36.75, 3.05, 0, 0, now, 8), 1))
        assertEquals(1, algiers shr 10 and 1)
        assertEquals(0, algiers shr 11 and 1)
        assertEquals(1, algiers shr 12 and 1, "positioned bit")
    }

    @Test
    fun courseOccupiesTheLowTenBits() {
        val status = courseStatus(Gt06Encoder.location(Gt06Fix(36.75, 3.05, 0, 359, now, 8), 1))
        assertEquals(359, status and 0x3FF)
    }

    @Test
    fun heartbeatIsProtocol0x13() {
        val packet = Gt06Encoder.heartbeat(serial = 2, batteryLevel0to6 = 6, gsmSignal0to4 = 4)
        assertEquals(0x13, packet[3].toInt())
        assertEquals(Crc16X25.compute(packet, 2, packet.size - 4), embeddedCrc(packet))
    }

    @Test
    fun ignitionFromStatusPacket() {
        val on = Gt06Encoder.heartbeat(1, batteryLevel0to6 = 6, gsmSignal0to4 = 4, ignition = true)
        val off = Gt06Encoder.heartbeat(1, batteryLevel0to6 = 6, gsmSignal0to4 = 4, ignition = false)
        // 78 78 len proto | terminal info: bit 1 = ACC (ignition) on
        assertEquals(0x13, on[3].toInt())
        assertEquals(0x02, on[4].toInt() and 0x02)
        assertEquals(0x00, off[4].toInt() and 0x02)
    }
}
