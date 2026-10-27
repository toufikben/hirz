package dz.hirz.sim

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc16X25Test {
    @Test
    fun checkValue() {
        assertEquals(0x906E, Crc16X25.compute("123456789".toByteArray()))
    }

    @Test
    fun computesOverARange() {
        val framed = byteArrayOf(0x00, 0x00) + "123456789".toByteArray() + byteArrayOf(0x00)
        assertEquals(0x906E, Crc16X25.compute(framed, 2, framed.size - 1))
    }
}
