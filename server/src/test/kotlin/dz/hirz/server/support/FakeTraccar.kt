package dz.hirz.server.support

import dz.hirz.server.traccar.TraccarAdmin
import dz.hirz.server.traccar.TraccarPosition
import dz.hirz.server.traccar.TraccarUnavailable
import dz.hirz.shared.api.TrackerClaimDto
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Records Core's calls to Traccar's REST API; [stored] is what `positions` returns, filtered by server time. */
class FakeTraccarAdmin : TraccarAdmin {
    private val ids = AtomicLong(100)
    val created = CopyOnWriteArrayList<Pair<Long, String>>()
    val deleted = CopyOnWriteArrayList<Long>()
    val stored = CopyOnWriteArrayList<TraccarPosition>()
    /** When true, createDevice fails as an unreachable Traccar would. */
    @Volatile var down = false
    @Volatile var status = "online"
    @Volatile var commandResult = dz.hirz.server.traccar.CommandResult.SENT
    val commands = CopyOnWriteArrayList<Pair<Long, String>>()
    val queuedDeleted = CopyOnWriteArrayList<Long>()

    override fun deviceStatus(id: Long): String = status
    override fun sendCommand(deviceId: Long, type: String): dz.hirz.server.traccar.CommandResult { commands += deviceId to type; return commandResult }
    override fun deleteQueued(deviceId: Long) { queuedDeleted += deviceId }

    override fun createDevice(uniqueId: String, name: String): Long {
        if (down) throw TraccarUnavailable("fake Traccar is down")
        return ids.incrementAndGet().also { created += it to uniqueId }
    }
    override fun deleteDevice(id: Long) { deleted += id }
    override fun positions(deviceId: Long, from: Instant, to: Instant): List<TraccarPosition> = stored.filter { p ->
        p.deviceId == deviceId && (p.serverTime?.let { OffsetDateTime.parse(it).toInstant() }?.let { !it.isBefore(from) && !it.isAfter(to) } ?: true)
    }
}

/** A Traccar position-forward body, as `forward.type=json` posts it. */
fun traccarForward(
    imei: String, id: Long, at: Instant, lat: Double = 36.7538, lon: Double = 3.0588,
    alarm: String? = null, outdated: Boolean = false, fixTime: Instant = at, deviceId: Long = 7,
): String = """{"position":{"id":$id,"deviceId":$deviceId,"protocol":"gt06","serverTime":"$at","fixTime":"$fixTime",
    "outdated":$outdated,"valid":true,"latitude":$lat,"longitude":$lon,"speed":0.0,"course":0.0,"accuracy":10.0,
    "attributes":{${alarm?.let { "\"alarm\":\"$it\"" } ?: ""}}},"device":{"id":$deviceId,"uniqueId":"$imei"}}"""

/**
 * Proves control the way a reconfigured tracker does: three fixes at the claim's challenge interval, from [start].
 * Returns the time of the third (activating) report.
 */
suspend fun HttpClient.activateTracker(imei: String, claim: TrackerClaimDto, start: Instant, deviceId: Long = 7, firstId: Long = 900): Instant {
    val step = claim.proofIntervalS!!.toLong()
    var at = start
    repeat(3) { i ->
        at = start.plusSeconds(step * i)
        post("/v1/traccar/positions") {
            bearerAuth(TRACCAR_SECRET); contentType(ContentType.Application.Json)
            setBody(traccarForward(imei, firstId + i, at, deviceId = deviceId))
        }
    }
    return at
}

/** [prefix] (14 digits) + its Luhn check digit: a valid IMEI. */
fun validImei(prefix: String): String {
    require(prefix.length == 14)
    val sum = prefix.reversed().mapIndexed { i, ch ->
        val d = ch - '0'
        if (i % 2 == 0) (d * 2).let { if (it > 9) it - 9 else it } else d
    }.sum()
    return prefix + ((10 - sum % 10) % 10)
}
