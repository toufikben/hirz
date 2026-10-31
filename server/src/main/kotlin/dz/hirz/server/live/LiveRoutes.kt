package dz.hirz.server.live

import dz.hirz.server.Deps
import dz.hirz.server.apiJson
import dz.hirz.server.assets.assetTypeOf
import dz.hirz.server.auth.memberFromToken
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.toList
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.shared.api.AssetLiveDto
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.LiveEvent
import dz.hirz.shared.api.PositionDto
import dz.hirz.shared.signal.FixSource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import dz.hirz.server.custody.SHARED_ASSETS_SQL
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

internal const val POSITION_COLUMNS =
    "p.asset_id, p.fix_time, p.server_time, ST_Y(p.geog::geometry), ST_X(p.geog::geometry), p.accuracy_m, p.accuracy_estimated, p.fix_source, p.speed_kmh, p.heading, p.battery"

internal fun positionRow(rs: ResultSet, at: Int = 1) = PositionDto(
    assetId = rs.getString(at), fixTimeMs = rs.getTimestamp(at + 1).time, serverTimeMs = rs.getTimestamp(at + 2).time,
    position = LatLon(rs.getDouble(at + 3), rs.getDouble(at + 4)),
    accuracyM = rs.getDouble(at + 5).takeUnless { rs.wasNull() }, accuracyEstimated = rs.getBoolean(at + 6),
    fixSource = rs.getString(at + 7)?.let { apiJson.decodeFromString(FixSource.serializer(), "\"$it\"") },
    speedKmh = rs.getDouble(at + 8).takeUnless { rs.wasNull() }, heading = rs.getDouble(at + 9).takeUnless { rs.wasNull() },
    battery = rs.getInt(at + 10).takeUnless { rs.wasNull() },
)

/** Writes the location access log (design §12.5.7). */
fun logView(c: Connection, memberId: UUID, assetId: UUID, via: String, at: java.time.Instant) {
    c.prepareStatement("INSERT INTO location_views (member_id, asset_id, via, at) VALUES (?, ?, ?, ?)")
        .apply { setObject(1, memberId); setObject(2, assetId); setString(3, via); setTimestamp(4, at.ts()) }.executeUpdate()
}

fun Route.liveRoutes(deps: Deps, hub: LiveHub, incidents: IncidentService) {
    webSocket("/v1/live") {
        val member = memberFromToken(deps.db, deps.verifier, call.request.queryParameters["token"])
        if (member == null) {
            close(CloseReason(LiveClose.UNAUTHORIZED, "invalid token"))
            return@webSocket
        }
        val now = deps.clock.instant()
        val handle = hub.register(member.memberId, this)
        try {
            val circles = hub.circlesOf(member.memberId, now.toEpochMilli())
            hub.setCircles(handle, circles)
            hub.send(handle, deps.db.connection.use { c -> snapshot(c, member.memberId, circles, incidents, now) })
            for (frame in incoming) { /* the app sends nothing; reading keeps the socket open until it closes */ }
        } finally {
            hub.unregister(handle)
        }
    }
}

private fun snapshot(c: Connection, memberId: UUID, circles: Set<UUID>, incidents: IncidentService, now: java.time.Instant): LiveEvent.Snapshot {
    if (circles.isEmpty()) return LiveEvent.Snapshot(emptyList(), emptyList(), now.toEpochMilli())
    val ids = c.createArrayOf("uuid", circles.toTypedArray())
    val latest = c.prepareStatement(
        """SELECT DISTINCT ON (p.asset_id) $POSITION_COLUMNS FROM positions p JOIN assets a ON a.id = p.asset_id
           WHERE (a.circle_id = ANY(?) OR a.id IN ($SHARED_ASSETS_SQL)) AND p.quality = 'ok' AND p.geog IS NOT NULL AND p.fix_time IS NOT NULL
           ORDER BY p.asset_id, p.fix_time DESC""",
    ).apply { setArray(1, ids); setArray(2, ids); setTimestamp(3, java.sql.Timestamp.from(now)) }.executeQuery().toList { positionRow(it) }.associateBy { it.assetId }
    val assets = c.prepareStatement(
        """SELECT a.id, a.circle_id, a.name, a.type, (SELECT max(s.last_heartbeat_at) FROM sources s WHERE s.asset_id = a.id AND s.revoked_at IS NULL)
           FROM assets a WHERE a.circle_id = ANY(?) OR a.id IN ($SHARED_ASSETS_SQL) ORDER BY a.created_at""",
    ).apply { setArray(1, ids); setArray(2, ids); setTimestamp(3, java.sql.Timestamp.from(now)) }.executeQuery().toList {
        AssetLiveDto(it.getString(1), it.getString(2), it.getString(3), assetTypeOf(it.getString(4)), latest[it.getString(1)], it.getTimestamp(5)?.time)
    }
    assets.forEach { logView(c, memberId, UUID.fromString(it.assetId), "live", now) }
    val open = c.prepareStatement(
        "SELECT id FROM incidents WHERE (circle_id = ANY(?) OR asset_id IN ($SHARED_ASSETS_SQL)) AND state = 'open' ORDER BY opened_at DESC",
    ).apply { setArray(1, ids); setArray(2, ids); setTimestamp(3, java.sql.Timestamp.from(now)) }.executeQuery().toList { it.getObject(1, UUID::class.java) }.map { incidents.load(c, it) }
    return LiveEvent.Snapshot(assets, open, now.toEpochMilli())
}

fun Route.historyRoutes(deps: Deps, circles: CircleService) {
    get("/v1/assets/{id}/positions") {
        val assetId = uuidOrNotFound(call.parameters["id"])
        val memberId = call.member().memberId
        val since = call.request.queryParameters["sinceMs"]?.toLongOrNull() ?: 0L
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 500).coerceIn(1, 2000)
        val rows = deps.db.connection.use { c ->
            val circleId = c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, assetId) }
                .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }
            circles.requireRole(c, circleId, memberId)
            logView(c, memberId, assetId, "history", deps.clock.instant())
            c.prepareStatement(
                """SELECT * FROM (SELECT $POSITION_COLUMNS FROM positions p
                     WHERE p.asset_id = ? AND p.quality = 'ok' AND p.geog IS NOT NULL AND p.fix_time >= to_timestamp(? / 1000.0)
                     ORDER BY p.fix_time DESC LIMIT ?) recent ORDER BY 2""",
            ).apply { setObject(1, assetId); setLong(2, since); setInt(3, limit) }.executeQuery().toList { positionRow(it) }
        }
        call.respond(rows)
    }
}
