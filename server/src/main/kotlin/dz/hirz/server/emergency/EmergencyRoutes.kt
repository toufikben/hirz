package dz.hirz.server.emergency

import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.shared.api.EmergencyConfirmRequest
import dz.hirz.shared.api.EmergencyNumberDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * Public emergency numbers per country (design §5.8). A number counts as verified for a circle once one of its
 * members confirmed it; a global `verified_at` (set by operators) applies to every circle.
 */
class EmergencyService(private val db: DataSource, private val clock: Clock, private val circles: CircleService) {
    fun list(country: String, circleId: UUID?, memberId: UUID): List<EmergencyNumberDto> = db.connection.use { c ->
        if (circleId != null) circles.requireRole(c, circleId, memberId)
        c.prepareStatement(
            """SELECT n.number, n.label, COALESCE(ec.at, n.verified_at) FROM emergency_numbers n
               LEFT JOIN emergency_confirmations ec ON ec.country = n.country AND ec.number = n.number AND ec.circle_id = ?
               WHERE n.country = ? ORDER BY n.number""",
        ).apply { setObject(1, circleId); setString(2, country.uppercase()) }
            .executeQuery().toList { EmergencyNumberDto(it.getString(1), it.getString(2), it.getTimestamp(3)?.time) }
    }

    fun confirm(number: String, circleId: UUID, memberId: UUID) = db.tx { c ->
        circles.requireRole(c, circleId, memberId)
        val country = c.prepareStatement("SELECT country FROM circles WHERE id = ?").apply { setObject(1, circleId) }
            .executeQuery().use { it.next(); it.getString(1) }
        val known = c.prepareStatement("SELECT 1 FROM emergency_numbers WHERE country = ? AND number = ?")
            .apply { setString(1, country); setString(2, number) }.executeQuery().use { it.next() }
        if (!known) notFound()
        c.prepareStatement(
            """INSERT INTO emergency_confirmations (circle_id, country, number, member_id, at) VALUES (?, ?, ?, ?, ?)
               ON CONFLICT (circle_id, number) DO UPDATE SET member_id = EXCLUDED.member_id, at = EXCLUDED.at""",
        ).apply { setObject(1, circleId); setString(2, country); setString(3, number); setObject(4, memberId); setTimestamp(5, clock.instant().ts()) }
            .executeUpdate()
        audit(c, circleId, memberId, "emergency_number_confirmed", """{"number":"$number"}""")
    }
}

fun Route.emergencyRoutes(service: EmergencyService) {
    get("/v1/emergency-numbers") {
        val country = call.request.queryParameters["country"] ?: "DZ"
        val circleId = call.request.queryParameters["circleId"]?.let { uuidOrNotFound(it) }
        call.respond(service.list(country, circleId, call.member().memberId))
    }
    post("/v1/emergency-numbers/{number}/confirm") {
        val number = call.parameters["number"].orEmpty()
        service.confirm(number, uuidOrNotFound(call.receive<EmergencyConfirmRequest>().circleId), call.member().memberId)
        call.respond(HttpStatusCode.NoContent)
    }
}
