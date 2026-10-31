package dz.hirz.shared.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BleFramesTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun eddystoneAndIBeaconParse() {
        // Eddystone-UID: frame 0x00, tx power, 10-byte namespace, 6-byte instance, 2 reserved.
        val uid = bytes(0x00, 0xEE, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 0, 0)
        assertEquals("eddystone:0102030405060708090a0b0c0d0e0f10", BleFrames.eddystone(uid))
        // Eddystone-TLM: version 0, battery 3000 mV, ... — not an identity.
        assertNull(BleFrames.eddystone(bytes(0x20, 0x00, 0x0B, 0xB8, 0, 0, 0, 0, 0, 1, 0, 0, 0, 2)))
        assertEquals(3000, BleFrames.tlmBatteryMv(bytes(0x20, 0x00, 0x0B, 0xB8, 0, 0, 0, 0, 0, 1, 0, 0, 0, 2)))
        // iBeacon in Apple manufacturer data (company 0x004C stripped): 0x02 0x15, UUID, major, minor, tx power.
        val ib = bytes(0x02, 0x15, 0xFD, 0xA5, 0x06, 0x93, 0xA4, 0xE2, 0x4F, 0xB1, 0xAF, 0xCF, 0xC6, 0xEB, 0x07, 0x64, 0x78, 0x25,
            0x00, 0x01, 0x00, 0x2A, 0xC5)
        assertEquals("ibeacon:fda50693-a4e2-4fb1-afcf-c6eb07647825:1:42", BleFrames.iBeacon(ib))
        assertNull(BleFrames.iBeacon(bytes(0x02, 0x15, 0x01)), "short frame")
    }

    @Test
    fun rangeFromRssi() {
        assertEquals(20.0, BleFrames.rangeM(-55))
        assertEquals(60.0, BleFrames.rangeM(-75))
        assertEquals(120.0, BleFrames.rangeM(-95))
    }
}
