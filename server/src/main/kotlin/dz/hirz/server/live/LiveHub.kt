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
import kotlinx.coroutines.launch
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
class LiveHub(private val db: DataSource, private val incidents: IncidentService) : IngestListener, IncidentEvents, CircleEvents {
    private class Conn(val memberId: UUID, val session: WebSocketSession, val circles: MutableSet<UUID>)

    private val conns = ConcurrentHashMap.newKeySet<Conn>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun register(memberId: UUID, session: WebSocketSession, circles: Set<UUID>): Any =
        Conn(memberId, session, circles.toMutableSet()).also { conns += it }

    fun unregister(handle: Any) { conns.remove(handle) }

    override fun onIngested(s: IngestedSignal) {
        val pos = s.signal.position ?: return
        val fix = s.signal.fixTimeMs ?: return
        if (s.quality != "ok" || !s.isLatestFix) return
        val dto = PositionDto(
            assetId = s.assetId.toString(), fixTimeMs = fix, serverTimeMs = s.signal.serverTimeMs,
            position = LatLon(pos.lat, pos.lon), accuracyM = pos.accuracyM, accuracyEstimated = pos.accuracyEstimated,
            fixSource = s.signal.fixSource, speedKmh = s.signal.speedKmh, heading = s.signal.heading, battery = s.signal.battery,
        )
        broadcast(s.circleId, LiveEvent.PositionUpdate(dto), viewedAsset = s.assetId)
    }

    override fun changed(circleId: UUID, incidentId: UUID) {
        val dto = db.connection.use { incidents.load(it, incidentId) }
        broadcast(circleId, LiveEvent.IncidentUpdate(dto))
    }

    override fun memberRemoved(circleId: UUID, memberId: UUID) {
        conns.filter { it.memberId == memberId && circleId in it.circles }.forEach { conn ->
            scope.launch {
                runCatching {
                    conn.session.send(Frame.Text(encode(LiveEvent.MemberRemoved(circleId.toString()))))
                    conn.session.close(CloseReason(LiveClose.REMOVED, "removed from circle"))
                }
            }
            conns.remove(conn)
        }
    }

    private fun broadcast(circleId: UUID, event: LiveEvent, viewedAsset: UUID? = null) {
        val text = encode(event)
        val targets = conns.filter { circleId in it.circles }
        if (viewedAsset != null && targets.isNotEmpty()) logViews(targets.map { it.memberId }.distinct(), viewedAsset)
        targets.forEach { conn -> scope.launch { runCatching { conn.session.send(Frame.Text(text)) } } }
    }

    private fun logViews(members: List<UUID>, assetId: UUID) = db.connection.use { c ->
        c.prepareStatement("INSERT INTO location_views (member_id, asset_id, via, at) VALUES (?, ?, 'live', now())").use { ps ->
            members.forEach { ps.setObject(1, it); ps.setObject(2, assetId); ps.addBatch() }
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
