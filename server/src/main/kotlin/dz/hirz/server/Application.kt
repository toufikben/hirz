package dz.hirz.server

import dz.hirz.server.auth.FirebaseTokenVerifier
import dz.hirz.server.auth.MEMBER_AUTH
import dz.hirz.server.auth.SOURCE_AUTH
import dz.hirz.server.auth.installAuth
import dz.hirz.server.config.AppConfig
import dz.hirz.server.db.connectDatabase
import dz.hirz.server.notify.DisabledNotifier
import dz.hirz.shared.api.ApiErrorDto
import dz.hirz.shared.rules.RuleConfig
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Clock

val apiJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

fun Application.module(deps: Deps) {
    install(ContentNegotiation) { json(apiJson) }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ApiErrorDto(e.code, e.message)) }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiErrorDto("bad_request", e.message)) }
        exception<SerializationException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiErrorDto("bad_request", e.message)) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiErrorDto("invalid", e.message)) }
    }
    installAuth(deps.db, deps.verifier)
    routing {
        healthRoutes(System.getProperty("hirz.version") ?: "dev")
        authenticate(MEMBER_AUTH) { meRoutes(deps) }
        authenticate(SOURCE_AUTH) { sourceSelfRoutes(deps) }
    }
}

fun main() {
    val cfg = AppConfig.fromEnv()
    val verifier = cfg.firebaseProjectId?.let { FirebaseTokenVerifier(FirebaseTokenVerifier.firebaseApp(it)) }
        ?: error("HIRZ_FIREBASE_PROJECT_ID is required: members sign in with Firebase")
    val deps = Deps(
        db = connectDatabase(cfg),
        verifier = verifier,
        notifier = DisabledNotifier,
        clock = Clock.systemUTC(),
        rules = RuleConfig.DEFAULT,
    )
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) { module(deps) }.start(wait = true)
}
