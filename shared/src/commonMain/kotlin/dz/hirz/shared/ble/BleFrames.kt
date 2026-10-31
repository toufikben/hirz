package dz.hirz.shared.ble

/**
 * Open BLE beacon formats (design decision §1.11): Eddystone-UID and iBeacon identify a tag; Eddystone-TLM carries its
 * battery. Closed networks (AirTag, SmartTag) are never read. Pure, so the phone and the tests share it.
 */
object BleFrames {
    private fun hex(b: ByteArray, from: Int, to: Int) = (from until to).joinToString("") { (b[it].toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    /** Eddystone service data (UUID 0xFEAA) → "eddystone:<namespace><instance>" for a UID frame, else null. */
    fun eddystone(serviceData: ByteArray): String? {
        if (serviceData.size < 18 || serviceData[0].toInt() != 0x00) return null
        return "eddystone:" + hex(serviceData, 2, 18)
    }

    /** Battery in millivolts from an Eddystone-TLM frame (0x20), or null. */
    fun tlmBatteryMv(serviceData: ByteArray): Int? {
        if (serviceData.size < 4 || serviceData[0].toInt() != 0x20) return null
        return u16(serviceData, 2).takeIf { it > 0 }
    }

    /** Apple manufacturer data (company 0x004C removed) → "ibeacon:<uuid>:<major>:<minor>", or null. */
    fun iBeacon(manufacturerData: ByteArray): String? {
        if (manufacturerData.size < 23 || manufacturerData[0].toInt() != 0x02 || manufacturerData[1].toInt() != 0x15) return null
        val u = hex(manufacturerData, 2, 18)
        val uuid = "${u.substring(0, 8)}-${u.substring(8, 12)}-${u.substring(12, 16)}-${u.substring(16, 20)}-${u.substring(20)}"
        return "ibeacon:$uuid:${u16(manufacturerData, 18)}:${u16(manufacturerData, 20)}"
    }

    /** A rough radio range from signal strength: strong, medium or weak (a starting value, to be measured). */
    fun rangeM(rssi: Int): Double = when {
        rssi >= -65 -> 20.0
        rssi >= -85 -> 60.0
        else -> 120.0
    }
}
