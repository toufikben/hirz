package dz.hirz.server.circles

import dz.hirz.server.crypto.DataCipher
import dz.hirz.server.db.tx
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.shared.api.CodeWordDto
import dz.hirz.shared.api.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import java.util.UUID
import javax.sql.DataSource

/**
 * The family code word (design §5.10): encrypted at rest, readable by members only, every read audited. It never
 * enters a push, an SMS, the incident sheet or the police pack.
 */
class CodeWordService(private val db: DataSource, private val circles: CircleService, private val cipher: DataCipher) {
    fun set(circleId: UUID, memberId: UUID, word: String) = db.tx { c ->
        circles.requireRole(c, circleId, memberId, setOf(Role.OWNER, Role.GUARDIAN))
        val w = word.trim()
        require(w.length in 3..40) { "code word must be 3–40 characters" }
        c.prepareStatement("UPDATE circles SET code_word_enc = ? WHERE id = ?").apply { setBytes(1, cipher.encrypt(w)); setObject(2, circleId) }
            .executeUpdate()
        audit(c, circleId, memberId, "code_word_set")
    }

    fun get(circleId: UUID, memberId: UUID): CodeWordDto = db.tx { c ->
        circles.requireRole(c, circleId, memberId, setOf(Role.OWNER, Role.GUARDIAN, Role.VIEWER, Role.TEMPORARY))
        val blob = c.prepareStatement("SELECT code_word_enc FROM circles WHERE id = ?").apply { setObject(1, circleId) }
            .executeQuery().use { if (it.next()) it.getBytes(1) else null } ?: notFound()
        audit(c, circleId, memberId, "code_word_viewed")
        CodeWordDto(cipher.decrypt(blob))
    }
}

fun Route.codeWordRoutes(service: CodeWordService) {
    put("/v1/circles/{id}/code-word") {
        service.set(uuidOrNotFound(call.parameters["id"]), call.member().memberId, call.receive<CodeWordDto>().word)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/v1/circles/{id}/code-word") {
        call.respond(service.get(uuidOrNotFound(call.parameters["id"]), call.member().memberId))
    }
}
