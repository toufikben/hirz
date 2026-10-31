package dz.hirz.server.routines

import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.RoutineDto
import dz.hirz.shared.api.RoutineRequest
import dz.hirz.shared.api.RoutineTemplate
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/** A routine mutes the place exits it expects: lowering an alert is the owner's call (P7 review). */
private val EDITORS = setOf(Role.OWNER)
private val READERS = setOf(Role.OWNER, Role.GUARDIAN, Role.VIEWER)
private val SCHOOL_WEEK = setOf(7, 1, 2, 3, 4)
private val EVERY_DAY = (1..7).toSet()

/** Routines (design §1, §6.3): what an asset's ordinary day looks like, so its absence can be noticed (P7). */
class RoutineService(private val db: DataSource, private val circles: CircleService) {
    companion object {
        /** A request with the template's defaults filled in where the request is silent. */
        fun filled(r: RoutineRequest): RoutineRequest = when (r.template) {
            null -> r
            RoutineTemplate.SCHOOL -> r.copy(days = r.days.ifEmpty { SCHOOL_WEEK }, arriveByMin = r.arriveByMin ?: 8 * 60, dismissalMin = r.dismissalMin ?: 16 * 60 + 30)
            RoutineTemplate.MOSQUE -> r.copy(days = r.days.ifEmpty { setOf(5) }, arriveByMin = r.arriveByMin ?: 13 * 60, dismissalMin = r.dismissalMin)
            RoutineTemplate.LESSONS -> r.copy(days = r.days.ifEmpty { setOf(6) }, arriveByMin = r.arriveByMin ?: 9 * 60, dismissalMin = r.dismissalMin ?: 12 * 60)
            RoutineTemplate.PASTURE_DAY -> r.copy(days = r.days.ifEmpty { EVERY_DAY }, arriveByMin = r.arriveByMin ?: 9 * 60, dismissalMin = r.dismissalMin ?: 17 * 60)
            RoutineTemplate.BARN_NIGHT -> r.copy(days = r.days.ifEmpty { EVERY_DAY }, arriveByMin = r.arriveByMin ?: 20 * 60, dismissalMin = r.dismissalMin)
        }

        fun dto(rs: ResultSet): RoutineDto = RoutineDto(
            id = rs.getString("id"), assetId = rs.getString("asset_id"), placeId = rs.getString("place_id"),
            days = (rs.getArray("days").array as Array<*>).map { (it as Number).toInt() }.toSet(),
            arriveByMin = rs.getInt("arrive_by_min").takeUnless { rs.wasNull() },
            dismissalMin = rs.getInt("dismissal_min").takeUnless { rs.wasNull() },
            ramadanOffsetMin = rs.getInt("ramadan_offset_min"), active = rs.getBoolean("active"),
        )

        /** The active routines of every asset, for the scanner. */
        fun active(c: Connection): List<RoutineDto> =
            c.prepareStatement("SELECT * FROM routines WHERE active").executeQuery().toList(::dto)
    }

    private fun circleOfAsset(c: Connection, assetId: UUID): UUID =
        c.prepareStatement("SELECT circle_id FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }

    private fun validated(c: Connection, circle: UUID, raw: RoutineRequest): Pair<RoutineRequest, UUID> {
        val r = filled(raw)
        if (r.days.isEmpty() || r.days.any { it !in 1..7 }) unprocessable("bad_days")
        if (r.arriveByMin == null && r.dismissalMin == null) unprocessable("no_times")
        if (listOfNotNull(r.arriveByMin, r.dismissalMin).any { it !in 0..1439 }) unprocessable("bad_time")
        if (r.ramadanOffsetMin !in -240..240) unprocessable("bad_offset")
        val place = runCatching { UUID.fromString(r.placeId) }.getOrNull() ?: notFound()
        val ok = c.prepareStatement("SELECT 1 FROM places WHERE id = ? AND circle_id = ?").apply { setObject(1, place); setObject(2, circle) }
            .executeQuery().use { it.next() }
        if (!ok) notFound()
        return r to place
    }

    fun list(assetId: UUID, memberId: UUID): List<RoutineDto> = db.tx { c ->
        circles.requireRole(c, circleOfAsset(c, assetId), memberId, READERS)
        c.prepareStatement("SELECT * FROM routines WHERE asset_id = ? ORDER BY created_at").apply { setObject(1, assetId) }.executeQuery().toList(::dto)
    }

    fun create(assetId: UUID, memberId: UUID, req: RoutineRequest): RoutineDto = db.tx { c ->
        val circle = circleOfAsset(c, assetId)
        circles.requireRole(c, circle, memberId, EDITORS)
        val (r, place) = validated(c, circle, req)
        val dto = c.prepareStatement(
            """INSERT INTO routines (asset_id, place_id, days, arrive_by_min, dismissal_min, ramadan_offset_min, active, created_by)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING *""",
        ).apply {
            setObject(1, assetId); setObject(2, place); setArray(3, c.createArrayOf("int4", r.days.sorted().toTypedArray()))
            setObject(4, r.arriveByMin); setObject(5, r.dismissalMin); setInt(6, r.ramadanOffsetMin); setBoolean(7, r.active); setObject(8, memberId)
        }.executeQuery().use { it.next(); dto(it) }
        audit(c, circle, memberId, "routine_changed", """{"routine":"${dto.id}","op":"create"}""")
        dto
    }

    private fun assetOfRoutine(c: Connection, routineId: UUID): UUID =
        c.prepareStatement("SELECT asset_id FROM routines WHERE id = ?").apply { setObject(1, routineId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else notFound() }

    fun update(routineId: UUID, memberId: UUID, req: RoutineRequest): RoutineDto = db.tx { c ->
        val circle = circleOfAsset(c, assetOfRoutine(c, routineId))
        circles.requireRole(c, circle, memberId, EDITORS)
        val (r, place) = validated(c, circle, req)
        val dto = c.prepareStatement(
            """UPDATE routines SET place_id = ?, days = ?, arrive_by_min = ?, dismissal_min = ?, ramadan_offset_min = ?, active = ?
               WHERE id = ? RETURNING *""",
        ).apply {
            setObject(1, place); setArray(2, c.createArrayOf("int4", r.days.sorted().toTypedArray())); setObject(3, r.arriveByMin)
            setObject(4, r.dismissalMin); setInt(5, r.ramadanOffsetMin); setBoolean(6, r.active); setObject(7, routineId)
        }.executeQuery().use { it.next(); dto(it) }
        audit(c, circle, memberId, "routine_changed", """{"routine":"$routineId","op":"update"}""")
        dto
    }

    fun delete(routineId: UUID, memberId: UUID) = db.tx { c ->
        val circle = circleOfAsset(c, assetOfRoutine(c, routineId))
        circles.requireRole(c, circle, memberId, EDITORS)
        c.prepareStatement("DELETE FROM routines WHERE id = ?").apply { setObject(1, routineId) }.executeUpdate()
        audit(c, circle, memberId, "routine_changed", """{"routine":"$routineId","op":"delete"}""")
    }
}

fun Route.routineRoutes(service: RoutineService) {
    get("/v1/assets/{id}/routines") { call.respond(service.list(uuidOrNotFound(call.parameters["id"]), call.member().memberId)) }
    post("/v1/assets/{id}/routines") {
        call.respond(service.create(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<RoutineRequest>()))
    }
    put("/v1/routines/{rid}") {
        call.respond(service.update(uuidOrNotFound(call.parameters["rid"]), call.member().memberId, call.receive<RoutineRequest>()))
    }
    delete("/v1/routines/{rid}") {
        service.delete(uuidOrNotFound(call.parameters["rid"]), call.member().memberId)
        call.respond(HttpStatusCode.NoContent)
    }
}
