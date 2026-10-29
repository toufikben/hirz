package dz.hirz.server

import dz.hirz.server.assets.AssetService
import dz.hirz.server.assets.assetRoutes
import dz.hirz.server.assets.pairRoutes
import dz.hirz.server.assets.sourcePushRoutes
import dz.hirz.server.auth.FirebaseTokenVerifier
import dz.hirz.server.auth.MEMBER_AUTH
import dz.hirz.server.auth.SOURCE_AUTH
import dz.hirz.server.auth.installAuth
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.circleRoutes
import dz.hirz.server.config.AppConfig
import dz.hirz.server.db.connectDatabase
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.incidents.incidentRoutes
import dz.hirz.server.ingest.IngestService
import dz.hirz.server.ingest.ingestRoutes
import dz.hirz.server.jobs.RetentionPurge
import dz.hirz.server.jobs.SilenceScanner
import dz.hirz.server.live.LiveHub
import dz.hirz.server.live.historyRoutes
import dz.hirz.server.live.liveRoutes
import dz.hirz.server.notify.FcmNotifier
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.rules.RulesEngine
import dz.hirz.server.places.PlaceService
import dz.hirz.server.places.placeRoutes
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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import kotlin.time.Duration.Companion.seconds
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
    install(WebSockets) { pingPeriod = 30.seconds }
    lateinit var hub: LiveHub
    val circles = CircleService(deps.db, deps.clock) { circleId, memberId -> hub.memberRemoved(circleId, memberId) }
    val assets = AssetService(deps.db, deps.clock, circles)
    val places = PlaceService(deps.db, circles, deps.rules.placeMinMarginM)
    val incidents = IncidentService(deps.db, deps.clock, deps.notifier, circles)
    hub = LiveHub(deps.db, incidents, deps.clock)
    val escalation = dz.hirz.server.escalation.EscalationEngine(deps.db, deps.clock, deps.notifier, deps.rules, circles)
    incidents.escalation = escalation
    incidents.subscribe(hub)
    val recorder = FindingRecorder(deps.db, deps.clock, incidents)
    val engine = RulesEngine(deps.db, deps.rules, recorder)
    val ingest = IngestService(deps.db, deps.clock, deps.rules, listOf(engine, hub) + deps.extraIngestListeners)
    if (deps.startJobs) {
        SilenceScanner(deps.db, deps.clock, deps.rules, recorder).start(this)
        RetentionPurge(deps.db, deps.clock).start(this)
        escalation.start(this)
    }
    routing {
        healthRoutes(System.getProperty("hirz.version") ?: "dev")
        pairRoutes(assets)
        liveRoutes(deps, hub, incidents)
        authenticate(MEMBER_AUTH) {
            meRoutes(deps)
            circleRoutes(circles)
            assetRoutes(assets)
            placeRoutes(places)
            incidentRoutes(incidents)
            historyRoutes(deps, circles)
        }
        authenticate(SOURCE_AUTH) {
            sourceSelfRoutes(deps)
            sourcePushRoutes(assets)
            ingestRoutes(ingest)
        }
    }
}

fun main() {
    val cfg = AppConfig.fromEnv()
    val firebase = FirebaseTokenVerifier.firebaseApp(
        cfg.firebaseProjectId ?: error("HIRZ_FIREBASE_PROJECT_ID is required: members sign in with Firebase"),
    )
    val deps = Deps(
        db = connectDatabase(cfg),
        verifier = FirebaseTokenVerifier(firebase),
        notifier = FcmNotifier(firebase),
        clock = Clock.systemUTC(),
        rules = RuleConfig.DEFAULT,
    )
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) { module(deps) }.start(wait = true)
}
