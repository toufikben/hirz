package dz.hirz.server.live

import dz.hirz.server.apiJson
import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.db.toList
import dz.hirz.server.incidents.IncidentEvents
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.ingest.IngestListener
import dz.hirz.server.ingest.IngestedSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.LiveEvent
import dz.hirz.shared.api.PositionDto
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/** Close codes for `WS /v1/live` (4000–4999 are application-defined). */
object LiveClose {
    const val UNAUTHORIZED: Short = 4401
    const val REMOVED: Short = 4403
}

/**
 * Fans live positions and incident changes out to the open sockets of a circle's members. A member removed
 * from a circle loses its sockets at once (design §8, Review Focus 5).
 */
class LiveHub(private val db: DataSource, private val incidents: IncidentService, private val clock: Clock) : IngestListener, IncidentEvents, CircleEvents {
    private sealed interface Out {
        data class Text(val text: String) : Out
        data class Close(val reason: CloseReason) : Out
    }

    /** Each socket has one sender draining one queue, so events reach it in the order they happened (P1 review #9). */
    private inner class Conn(val memberId: UUID, val session: WebSocketSession, val circles: MutableSet<UUID>) {
        val out = Channel<Out>(Channel.UNLIMITED)
        val sender = scope.launch {
            for (o in out) {
                runCatching {
                    when (o) {
                        is Out.Text -> session.send(Frame.Text(o.text))
                        is Out.Close -> { session.close(o.reason); out.close() }
                    }
                }
            }
        }
    }

    private val conns = ConcurrentHashMap.newKeySet<Conn>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Registers first, then resolves circles, so a removal racing with the connect cannot be missed. */
    fun register(memberId: UUID, session: WebSocketSession): Any =
        Conn(memberId, session, mutableSetOf()).also { conns += it }

    fun setCircles(handle: Any, circles: Set<UUID>) { (handle as Conn).circles += circles }

    fun send(handle: Any, event: LiveEvent) { (handle as Conn).out.trySend(Out.Text(encode(event))) }

    fun unregister(handle: Any) {
        (handle as Conn).out.close()
        conns.remove(handle)
    }

    override fun onIngested(s: IngestedSignal) {
        val pos = s.signal.position ?: return
        val fix = s.signal.fixTimeMs ?: return
        if (s.quality != "ok" || !s.isLatestFix) return
        val dto = PositionDto(
            assetId = s.assetId.toString(), fixTimeMs = fix, serverTimeMs = s.signal.serverTimeMs,
            position = LatLon(pos.lat, pos.lon), accuracyM = pos.accuracyM, accuracyEstimated = pos.accuracyEstimated,
            fixSource = s.signal.fixSource, speedKmh = s.signal.speedKmh, heading = s.signal.heading, battery = s.signal.battery,
        )
        val event = LiveEvent.PositionUpdate(dto)
        circlesSeeing(s.assetId).forEach { broadcast(it, event, viewedAsset = s.assetId) }
    }

    override fun changed(circleId: UUID, incidentId: UUID) {
        val dto = db.connection.use { incidents.load(it, incidentId) }
        val event = LiveEvent.IncidentUpdate(dto)
        (circlesSeeing(UUID.fromString(dto.assetId)) + circleId).distinct().forEach { broadcast(it, event) }
    }

    /** The asset's own circle and every circle sharing its custody (design §12.1). */
    private fun circlesSeeing(assetId: UUID): List<UUID> = db.connection.use { dz.hirz.server.custody.AssetAccess.circlesSeeing(it, assetId) }

    override fun memberRemoved(circleId: UUID, memberId: UUID) {
        conns.filter { it.memberId == memberId && circleId in it.circles }.forEach { kick(it, circleId) }
    }

    private fun kick(conn: Conn, circleId: UUID) {
        conn.out.trySend(Out.Text(encode(LiveEvent.MemberRemoved(circleId.toString()))))
        conn.out.trySend(Out.Close(CloseReason(LiveClose.REMOVED, "no longer a member")))
        conns.remove(conn)
    }

    /** Membership is re-checked at every broadcast: an expired temporary member loses an open socket (P1 review #8). */
    private fun broadcast(circleId: UUID, event: LiveEvent, viewedAsset: UUID? = null) {
        val candidates = conns.filter { circleId in it.circles }
        if (candidates.isEmpty()) return
        val active = activeMembers(circleId)
        val (allowed, expired) = candidates.partition { it.memberId in active }
        expired.forEach { kick(it, circleId) }
        if (viewedAsset != null && allowed.isNotEmpty()) logViews(allowed.map { it.memberId }.distinct(), viewedAsset)
        val text = encode(event)
        allowed.forEach { it.out.trySend(Out.Text(text)) }
    }

    private fun activeMembers(circleId: UUID): Set<UUID> = db.connection.use { c ->
        c.prepareStatement("SELECT member_id FROM circle_members WHERE circle_id = ? AND (expires_at IS NULL OR expires_at > ?)")
            .apply { setObject(1, circleId); setTimestamp(2, java.sql.Timestamp.from(clock.instant())) }
            .executeQuery().toList { it.getObject(1, UUID::class.java) }.toSet()
    }

    private fun logViews(members: List<UUID>, assetId: UUID) = db.connection.use { c ->
        c.prepareStatement("INSERT INTO location_views (member_id, asset_id, via, at) VALUES (?, ?, 'live', ?)").use { ps ->
            members.forEach { ps.setObject(1, it); ps.setObject(2, assetId); ps.setTimestamp(3, java.sql.Timestamp.from(clock.instant())); ps.addBatch() }
            ps.executeBatch()
        }
    }

    private fun encode(e: LiveEvent) = apiJson.encodeToString(LiveEvent.serializer(), e)

    /** Circles where [memberId] is an active member. */
    fun circlesOf(memberId: UUID, nowMs: Long): Set<UUID> = db.connection.use { c ->
        c.prepareStatement("SELECT circle_id FROM circle_members WHERE member_id = ? AND (expires_at IS NULL OR expires_at > to_timestamp(? / 1000.0))")
            .apply { setObject(1, memberId); setLong(2, nowMs) }.executeQuery().toList { it.getObject(1, UUID::class.java) }.toSet()
    }
}
