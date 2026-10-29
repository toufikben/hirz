package dz.hirz.server

import dz.hirz.server.auth.TokenVerifier
import dz.hirz.server.ingest.IngestListener
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
    /** 32-byte key for secrets at rest (HIRZ_DATA_KEY). */
    val dataKey: ByteArray,
    /** Where public share links point, e.g. https://hirz.example; no trailing slash needed. */
    val publicBaseUrl: String = "http://localhost:8080",
    val startJobs: Boolean = true,
    /** Extra observers of accepted signals (tests); the rules engine and live hub are wired inside the module. */
    val extraIngestListeners: List<IngestListener> = emptyList(),
)

/** A failure with a stable machine code for clients, rendered by StatusPages. */
class ApiException(val status: HttpStatusCode, val code: String, message: String? = null) : RuntimeException(message ?: code)

fun notFound(): Nothing = throw ApiException(HttpStatusCode.NotFound, "not_found")
fun badRequest(code: String, message: String? = null): Nothing = throw ApiException(HttpStatusCode.BadRequest, code, message)
fun conflict(code: String): Nothing = throw ApiException(HttpStatusCode.Conflict, code)
fun gone(code: String): Nothing = throw ApiException(HttpStatusCode.Gone, code)
fun unprocessable(code: String): Nothing = throw ApiException(HttpStatusCode.UnprocessableEntity, code)
