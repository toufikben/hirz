package dz.hirz.server.digest

import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.toList
import dz.hirz.server.member
import dz.hirz.server.db.tx
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.DigestDto
import dz.hirz.shared.api.DigestFeedbackRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.post
import dz.hirz.shared.api.DigestItem
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.sql.DataSource

/** The circle's evening digests, newest first (last 30). Any member may read them. */
class DigestService(private val db: DataSource, private val circles: CircleService, private val clock: java.time.Clock = java.time.Clock.systemUTC()) {
    /** A member says whether line [DigestFeedbackRequest.index] was useful (P8); the last answer counts. */
    fun feedback(digestId: UUID, memberId: UUID, req: DigestFeedbackRequest) = db.tx { c ->
        val (circle, size) = c.prepareStatement("SELECT circle_id, jsonb_array_length(items) FROM digests WHERE id = ?").apply { setObject(1, digestId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) to it.getInt(2) else notFound() }
        circles.requireRole(c, circle, memberId)
        if (req.index !in 0 until size) unprocessable("no_such_line")
        c.prepareStatement(
            """INSERT INTO digest_feedback (digest_id, item_index, member_id, useful, at) VALUES (?, ?, ?, ?, ?)
               ON CONFLICT (digest_id, item_index, member_id) DO UPDATE SET useful = EXCLUDED.useful, at = EXCLUDED.at""",
        ).apply { setObject(1, digestId); setInt(2, req.index); setObject(3, memberId); setBoolean(4, req.useful); setTimestamp(5, clock.instant().ts()) }
            .executeUpdate()
    }

    fun list(circleId: UUID, memberId: UUID): List<DigestDto> = db.connection.use { c ->
        circles.requireRole(c, circleId, memberId)
        c.prepareStatement("SELECT id, day, items FROM digests WHERE circle_id = ? ORDER BY day DESC LIMIT 30")
            .apply { setObject(1, circleId) }.executeQuery()
            .toList { DigestDto(it.getString(1), it.getDate(2).toLocalDate().toString(), Json.decodeFromString<List<DigestItem>>(it.getString(3))) }
    }
}

fun Route.digestRoutes(service: DigestService) {
    get("/v1/circles/{id}/digests") {
        call.respond(service.list(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
    post("/v1/digests/{id}/feedback") {
        service.feedback(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<DigestFeedbackRequest>())
        call.respond(HttpStatusCode.NoContent)
    }
}
