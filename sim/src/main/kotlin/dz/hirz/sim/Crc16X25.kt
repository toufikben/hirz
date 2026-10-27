package dz.hirz.sim

/** CRC-16/X-25 (CRC-ITU as used by the GT06 protocol): poly 0x1021 reflected, init 0xFFFF, xorout 0xFFFF. */
object Crc16X25 {
    fun compute(bytes: ByteArray, from: Int = 0, toExclusive: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (i in from until toExclusive) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
            }
        }
        return crc.inv() and 0xFFFF
    }
}
