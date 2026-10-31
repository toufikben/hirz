package dz.hirz.server.digest

import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.toList
import dz.hirz.server.member
import dz.hirz.shared.api.DigestDto
import dz.hirz.shared.api.DigestItem
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.sql.DataSource

/** The circle's evening digests, newest first (last 30). Any member may read them. */
class DigestService(private val db: DataSource, private val circles: CircleService) {
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
}
