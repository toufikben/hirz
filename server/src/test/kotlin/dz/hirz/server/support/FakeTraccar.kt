package dz.hirz.server.support

import dz.hirz.server.traccar.TraccarAdmin
import dz.hirz.server.traccar.TraccarPosition
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Records Core's calls to Traccar's REST API; [stored] is what `positions` returns. */
class FakeTraccarAdmin : TraccarAdmin {
    private val ids = AtomicLong(100)
    val created = CopyOnWriteArrayList<Pair<Long, String>>()
    val deleted = CopyOnWriteArrayList<Long>()
    val stored = CopyOnWriteArrayList<TraccarPosition>()

    override fun createDevice(uniqueId: String, name: String): Long = ids.incrementAndGet().also { created += it to uniqueId }
    override fun deleteDevice(id: Long) { deleted += id }
    override fun positions(deviceId: Long, from: Instant, to: Instant): List<TraccarPosition> = stored.filter { it.deviceId == deviceId }
}

/** A Traccar position-forward body, as `forward.type=json` posts it. */
fun traccarForward(
    imei: String, id: Long, at: Instant, lat: Double = 36.7538, lon: Double = 3.0588,
    alarm: String? = null, outdated: Boolean = false, fixTime: Instant = at, deviceId: Long = 7,
): String = """{"position":{"id":$id,"deviceId":$deviceId,"protocol":"gt06","serverTime":"$at","fixTime":"$fixTime",
    "outdated":$outdated,"valid":true,"latitude":$lat,"longitude":$lon,"speed":0.0,"course":0.0,"accuracy":10.0,
    "attributes":{${alarm?.let { "\"alarm\":\"$it\"" } ?: ""}}},"device":{"id":$deviceId,"uniqueId":"$imei"}}"""

/** [prefix] (14 digits) + its Luhn check digit: a valid IMEI. */
fun validImei(prefix: String): String {
    require(prefix.length == 14)
    val sum = prefix.reversed().mapIndexed { i, ch ->
        val d = ch - '0'
        if (i % 2 == 0) (d * 2).let { if (it > 9) it - 9 else it } else d
    }.sum()
    return prefix + ((10 - sum % 10) % 10)
}
