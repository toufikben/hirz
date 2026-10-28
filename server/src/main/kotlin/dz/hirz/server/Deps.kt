package dz.hirz.server

import dz.hirz.server.auth.TokenVerifier
import dz.hirz.server.notify.Notifier
import dz.hirz.shared.rules.RuleConfig
import io.ktor.http.HttpStatusCode
import java.time.Clock
import javax.sql.DataSource

/** Everything the application needs from outside; tests swap in fakes. */
data class Deps(
    val db: DataSource,
    val verifier: TokenVerifier,
    val notifier: Notifier,
    val clock: Clock,
    val rules: RuleConfig,
    val startJobs: Boolean = true,
)

/** A failure with a stable machine code for clients, rendered by StatusPages. */
class ApiException(val status: HttpStatusCode, val code: String, message: String? = null) : RuntimeException(message ?: code)

fun notFound(): Nothing = throw ApiException(HttpStatusCode.NotFound, "not_found")
fun badRequest(code: String, message: String? = null): Nothing = throw ApiException(HttpStatusCode.BadRequest, code, message)
fun conflict(code: String): Nothing = throw ApiException(HttpStatusCode.Conflict, code)
fun gone(code: String): Nothing = throw ApiException(HttpStatusCode.Gone, code)
fun unprocessable(code: String): Nothing = throw ApiException(HttpStatusCode.UnprocessableEntity, code)
