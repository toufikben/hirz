package dz.hirz.server.buses

import dz.hirz.server.assets.assetTypeOf
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.BusRouteDto
import dz.hirz.shared.api.BusRouteRequest
import dz.hirz.shared.api.BusStopDto
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.Role
import dz.hirz.shared.asset.AssetType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

private val EDITORS = setOf(Role.OWNER, Role.GUARDIAN)
private val READERS = setOf(Role.OWNER, Role.GUARDIAN, Role.VIEWER)
private val PATH = ListSerializer(LatLon.serializer())
private val ALGIERS = ZoneId.of("Africa/Algiers")

/** Bus runs (P9): routes with a corridor and ordered stops, edited by the bus's circle owners and guardians. */
class BusService(private val db: DataSource, private val circles: CircleService) {
    private fun busCircle(c: Connection, assetId: UUID): Pair<UUID, AssetType> =
        c.prepareStatement("SELECT circle_id, type FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) to assetTypeOf(it.getString(2)) else notFound() }

    fun create(assetId: UUID, memberId: UUID, req: BusRouteRequest): BusRouteDto = db.tx { c ->
        val (circle, type) = busCircle(c, assetId)
        circles.requireRole(c, circle, memberId, EDITORS)
        if (type != AssetType.BUS) unprocessable("not_a_bus")
        if (req.name.isBlank() || req.path.size < 2 || req.widthM !in 20.0..500.0) unprocessable("bad_route")
        if (req.days.isEmpty() || req.days.any { it !in 1..7 } || req.startMin !in 0..1439 || req.endMin !in 1..1440 || req.startMin >= req.endMin) unprocessable("bad_window")
        if (req.stops.isEmpty() || req.stops.zipWithNext().any { (a, b) -> b.dueMin <= a.dueMin } ||
            req.stops.any { it.dueMin !in req.startMin until req.endMin || it.radiusM !in 10.0..300.0 || it.name.isBlank() }) unprocessable("bad_stops")
        val id = c.prepareStatement(
            """INSERT INTO bus_routes (asset_id, name, path, width_m, days, start_min, end_min, created_by)
               VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?) RETURNING id""",
        ).apply {
            setObject(1, assetId); setString(2, req.name.trim().take(80)); setString(3, Json.encodeToString(PATH, req.path)); setDouble(4, req.widthM)
            setArray(5, c.createArrayOf("int", req.days.sorted().toTypedArray())); setInt(6, req.startMin); setInt(7, req.endMin); setObject(8, memberId)
        }.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
        req.stops.forEachIndexed { i, s ->
            c.prepareStatement("INSERT INTO route_stops (route_id, seq, name, lat, lon, radius_m, due_min) VALUES (?, ?, ?, ?, ?, ?, ?)").apply {
                setObject(1, id); setInt(2, i); setString(3, s.name.trim().take(80)); setDouble(4, s.at.lat); setDouble(5, s.at.lon)
                setDouble(6, s.radiusM); setInt(7, s.dueMin)
            }.executeUpdate()
        }
        audit(c, circle, memberId, "bus_route_created", """{"asset":"$assetId","route":"$id"}""")
        routes(c, assetId).single { it.id == id.toString() }
    }

    fun list(assetId: UUID, memberId: UUID): List<BusRouteDto> = db.connection.use { c ->
        circles.requireRole(c, busCircle(c, assetId).first, memberId, READERS)
        routes(c, assetId)
    }

    fun delete(routeId: UUID, memberId: UUID) = db.tx { c ->
        val asset = c.prepareStatement("SELECT asset_id FROM bus_routes WHERE id = ?").apply { setObject(1, routeId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }
        val circle = busCircle(c, asset).first
        circles.requireRole(c, circle, memberId, EDITORS)
        c.prepareStatement("DELETE FROM bus_routes WHERE id = ?").apply { setObject(1, routeId) }.executeUpdate()
        audit(c, circle, memberId, "bus_route_deleted", """{"route":"$routeId"}""")
    }

    companion object {
        fun routes(c: Connection, assetId: UUID): List<BusRouteDto> {
            val stops = c.prepareStatement(
                "SELECT s.route_id, s.seq, s.name, s.lat, s.lon, s.radius_m, s.due_min FROM route_stops s JOIN bus_routes r ON r.id = s.route_id WHERE r.asset_id = ? ORDER BY s.seq",
            ).apply { setObject(1, assetId) }.executeQuery()
                .toList { it.getString(1) to BusStopDto(it.getInt(2), it.getString(3), LatLon(it.getDouble(4), it.getDouble(5)), it.getDouble(6), it.getInt(7)) }
                .groupBy({ it.first }, { it.second })
            return c.prepareStatement("SELECT id, name, path, width_m, days, start_min, end_min FROM bus_routes WHERE asset_id = ? ORDER BY start_min")
                .apply { setObject(1, assetId) }.executeQuery().toList {
                    val id = it.getString(1)
                    BusRouteDto(id, assetId.toString(), it.getString(2), Json.decodeFromString(PATH, it.getString(3)), it.getDouble(4),
                        (it.getArray(5).array as Array<*>).map { d -> (d as Number).toInt() }.toSet(), it.getInt(6), it.getInt(7), stops[id].orEmpty())
                }
        }

        /** The route the bus is running at [at], if any. */
        fun running(c: Connection, assetId: UUID, at: Instant): BusRouteDto? {
            val local = at.atZone(ALGIERS)
            val minute = local.hour * 60 + local.minute
            return routes(c, assetId).firstOrNull { local.dayOfWeek.value in it.days && minute >= it.startMin && minute < it.endMin }
        }
    }
}

fun Route.busRoutes(service: BusService) {
    post("/v1/assets/{id}/bus-routes") {
        call.respond(service.create(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<BusRouteRequest>()))
    }
    get("/v1/assets/{id}/bus-routes") { call.respond(service.list(uuidOrNotFound(call.parameters["id"]), call.member().memberId)) }
    delete("/v1/bus-routes/{rid}") {
        service.delete(uuidOrNotFound(call.parameters["rid"]), call.member().memberId)
        call.respond(HttpStatusCode.NoContent)
    }
}
