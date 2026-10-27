package dz.hirz.sim

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToLong

/** One simulated fix. Coordinates in decimal degrees (WGS84), time in UTC. */
data class Gt06Fix(
    val lat: Double,
    val lon: Double,
    val speedKmh: Int,
    val courseDeg: Int,
    val utc: Instant,
    val satellites: Int,
    val positioned: Boolean = true,
)

/** Encodes GT06 (Concox) frames: `78 78 <len> <proto> <payload> <serial:2> <crc:2> 0D 0A`. */
object Gt06Encoder {
    private const val MCC_ALGERIA = 603
    private const val MNC = 1

    fun login(imei: String, serial: Int): ByteArray {
        require(imei.length in 15..16 && imei.all(Char::isDigit)) { "IMEI must be 15–16 digits: $imei" }
        val padded = imei.padStart(16, '0')
        val bcd = ByteArray(8) { i ->
            ((padded[2 * i].digitToInt() shl 4) or padded[2 * i + 1].digitToInt()).toByte()
        }
        return frame(0x01, bcd, serial)
    }

    fun heartbeat(serial: Int, batteryLevel0to6: Int, gsmSignal0to4: Int): ByteArray {
        val payload = byteArrayOf(
            0x40, // terminal info: GPS tracking on
            batteryLevel0to6.coerceIn(0, 6).toByte(),
            gsmSignal0to4.coerceIn(0, 4).toByte(),
            0x00, 0x01, // alarm/language: normal, English
        )
        return frame(0x13, payload, serial)
    }

    fun location(fix: Gt06Fix, serial: Int): ByteArray {
        val time = fix.utc.atOffset(ZoneOffset.UTC)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(
            (time.year % 100).toByte(), time.monthValue.toByte(), time.dayOfMonth.toByte(),
            time.hour.toByte(), time.minute.toByte(), time.second.toByte(),
        ))
        out.write(0xC0 or fix.satellites.coerceIn(0, 15))
        out.writeInt32(encodeCoordinate(fix.lat))
        out.writeInt32(encodeCoordinate(fix.lon))
        out.write(fix.speedKmh.coerceIn(0, 255))
        var status = fix.courseDeg.coerceIn(0, 359)
        if (fix.lat >= 0) status = status or (1 shl 10)
        if (fix.lon < 0) status = status or (1 shl 11)
        if (fix.positioned) status = status or (1 shl 12)
        out.writeInt16(status)
        out.writeInt16(MCC_ALGERIA)
        out.write(MNC)
        out.writeInt16(0) // LAC
        out.write(byteArrayOf(0, 0, 0)) // cell id
        return frame(0x12, out.toByteArray(), serial)
    }

    /** GT06 coordinate unit: |degrees| × 60 × 30000. The hemisphere lives in the course/status word. */
    fun encodeCoordinate(degrees: Double): Long = (abs(degrees) * 1_800_000).roundToLong()

    private fun frame(protocol: Int, payload: ByteArray, serial: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(1 + payload.size + 2 + 2) // length: protocol + payload + serial + crc
        body.write(protocol)
        body.write(payload)
        body.writeInt16(serial and 0xFFFF)
        val content = body.toByteArray()
        val crc = Crc16X25.compute(content)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x78, 0x78))
        out.write(content)
        out.writeInt16(crc)
        out.write(byteArrayOf(0x0D, 0x0A))
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt16(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt32(value: Long) {
        for (shift in intArrayOf(24, 16, 8, 0)) write(((value shr shift) and 0xFF).toInt())
    }
}
