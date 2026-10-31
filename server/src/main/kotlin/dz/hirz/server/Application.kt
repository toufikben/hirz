package dz.hirz.server

import dz.hirz.server.vehicles.VehicleModes
import dz.hirz.server.vehicles.VehicleStateTracker
import dz.hirz.server.vehicles.vehicleRoutes
import dz.hirz.server.traccar.TrackerService
import dz.hirz.server.traccar.traccarForwardRoutes
import dz.hirz.server.traccar.trackerRoutes
import dz.hirz.server.custody.CustodyService
import dz.hirz.server.custody.custodyRoutes
import dz.hirz.server.sms.GatewayService
import dz.hirz.server.sms.gatewayMemberRoutes
import dz.hirz.server.sms.gatewayPairRoutes
import dz.hirz.server.sms.gatewayRoutes
import dz.hirz.server.sms.installGatewayAuth
import dz.hirz.server.share.ShareService
import dz.hirz.server.share.publicShareRoutes
import dz.hirz.server.share.shareRoutes
import dz.hirz.server.exports.PolicePack
import dz.hirz.server.exports.exportRoutes
import dz.hirz.server.assets.AssetMediaService
import dz.hirz.server.assets.assetMediaRoutes
import dz.hirz.server.incidents.SheetService
import dz.hirz.server.incidents.sheetRoutes
import dz.hirz.server.circles.codeWordRoutes
import dz.hirz.server.emergency.emergencyRoutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dz.hirz.server.assets.AssetService
import dz.hirz.server.assets.assetRoutes
import dz.hirz.server.assets.profileRoutes
import dz.hirz.server.herds.herdRoutes
import dz.hirz.server.routines.routineRoutes
import dz.hirz.server.ble.bleMemberRoutes
import dz.hirz.server.ble.bleGrantRoutes
import dz.hirz.server.presence.outingRoutes
import dz.hirz.server.ble.bleRelayRoutes
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
    val incidents = IncidentService(deps.db, deps.clock, deps.notifier, circles, deps.rules)
    val codeWords = dz.hirz.server.circles.CodeWordService(deps.db, circles, dz.hirz.server.crypto.DataCipher(deps.dataKey))
    val emergency = dz.hirz.server.emergency.EmergencyService(deps.db, deps.clock, circles)
    val media = AssetMediaService(deps.db, deps.clock, circles)
    val custody = CustodyService(deps.db, deps.clock, circles, deps.notifier)
    val gateways = GatewayService(deps.db, deps.clock, circles, incidents)
    installGatewayAuth(gateways)
    val shares = ShareService(deps.db, deps.clock, circles, deps.publicBaseUrl, deps.dataKey)
    val sheets = SheetService(deps.db, deps.clock, circles, incidents, emergency).also { it.shareActiveUntil = shares::activeUntil }
    val policePack = PolicePack(deps.db, deps.clock, circles, incidents, System.getProperty("hirz.version") ?: "dev")
    hub = LiveHub(deps.db, incidents, deps.clock)
    val escalation = dz.hirz.server.escalation.EscalationEngine(deps.db, deps.clock, deps.notifier, deps.rules, circles)
    escalation.ordering = dz.hirz.server.escalation.NearestOrdering(deps.rules)
    incidents.escalation = escalation
    incidents.subscribe(hub)
    val recorder = FindingRecorder(deps.db, deps.clock, incidents)
    val vehicleModes = VehicleModes(deps.db, deps.clock, circles, deps.rules)
    val engine = RulesEngine(deps.db, deps.rules, recorder, vehicleModes)
    // The rules judge a report against the mode as it was before it (an engine starting on an auto-armed car is theft);
    // only then does the tracker record the new ignition state.
    val ingest = IngestService(deps.db, deps.clock, deps.rules, listOf(engine, VehicleStateTracker(deps.db, vehicleModes), hub) + deps.extraIngestListeners)
    val ble = dz.hirz.server.ble.BleService(deps.db, deps.clock, circles, ingest)
    val trackers = TrackerService(deps.db, deps.clock, circles, deps.traccar, deps.deviceHost, deps.claimProofS)
    if (deps.startJobs) dz.hirz.server.traccar.TraccarSync(deps.db, deps.clock, deps.traccar, ingest).start(this)
    if (deps.startJobs) {
        SilenceScanner(deps.db, deps.clock, deps.rules, recorder).start(this)
        dz.hirz.server.rules.CheckInScanner(deps.db, deps.clock, recorder).start(this)
        dz.hirz.server.jobs.JammingScanner(deps.db, deps.clock, deps.rules, vehicleModes, recorder).start(this)
        dz.hirz.server.jobs.CurfewScanner(deps.db, deps.clock, deps.rules, recorder).start(this)
        dz.hirz.server.jobs.HerdScanner(deps.db, deps.clock, deps.rules, recorder).start(this)
        dz.hirz.server.jobs.LeashScanner(deps.db, deps.clock, deps.rules, recorder).start(this)
        dz.hirz.server.jobs.RoutineScanner(deps.db, deps.clock, deps.rules, recorder).start(this)
        dz.hirz.server.jobs.LearningJob(deps.db, deps.clock, deps.rules).start(this)
        dz.hirz.server.jobs.AgeTransitionJob(deps.db, deps.clock, deps.notifier).start(this)
        dz.hirz.server.sms.GatewaySilenceScanner(deps.db, deps.clock, deps.notifier).start(this)
        RetentionPurge(deps.db, deps.clock).start(this)
        escalation.start(this)
        launch {
            while (isActive) {
                runCatching { trackers.expireClaims() }
                delay(60_000)
            }
        }
        launch {
            while (isActive) {
                runCatching { incidents.expireCloseRequests() }
                delay(5_000)
            }
        }
    }
    routing {
        healthRoutes(System.getProperty("hirz.version") ?: "dev")
        pairRoutes(assets)
        publicShareRoutes(shares)
        gatewayPairRoutes(gateways)
        traccarForwardRoutes(deps.traccarSecret, trackers, ingest)
        gatewayRoutes(gateways)
        liveRoutes(deps, hub, incidents)
        authenticate(MEMBER_AUTH) {
            meRoutes(deps)
            circleRoutes(circles)
            assetRoutes(assets)
            profileRoutes(dz.hirz.server.assets.ProfileService(deps.db, circles))
            herdRoutes(dz.hirz.server.herds.HerdService(deps.db, circles))
            routineRoutes(dz.hirz.server.routines.RoutineService(deps.db, circles))
            bleMemberRoutes(ble)
            bleGrantRoutes(ble)
            outingRoutes(dz.hirz.server.presence.Outings(deps.db, deps.clock, circles))
            placeRoutes(places)
            incidentRoutes(incidents)
            codeWordRoutes(codeWords)
            emergencyRoutes(emergency)
            assetMediaRoutes(media)
            sheetRoutes(sheets)
            exportRoutes(policePack)
            shareRoutes(shares)
            gatewayMemberRoutes(gateways)
            custodyRoutes(custody)
            trackerRoutes(trackers)
            vehicleRoutes(vehicleModes, dz.hirz.server.vehicles.RemoteEngine(deps.db, deps.clock, circles, deps.traccar))
            historyRoutes(deps, circles)
        }
        authenticate(SOURCE_AUTH) {
            sourceSelfRoutes(deps)
            sourcePushRoutes(assets)
            ingestRoutes(ingest)
            bleRelayRoutes(ble)
        }
    }
}

/** The Traccar admin client when all three settings are present; none of them means no Traccar (local runs). */
private fun traccarAdmin(cfg: AppConfig): dz.hirz.server.traccar.TraccarAdmin {
    val parts = listOf(cfg.traccarUrl, cfg.traccarUser, cfg.traccarPassword)
    if (parts.all { it == null }) return dz.hirz.server.traccar.TraccarAdmin.NONE
    require(parts.none { it.isNullOrBlank() }) { "HIRZ_TRACCAR_URL, HIRZ_TRACCAR_USER and HIRZ_TRACCAR_PASSWORD go together" }
    return dz.hirz.server.traccar.TraccarHttpAdmin(cfg.traccarUrl!!, cfg.traccarUser!!, cfg.traccarPassword!!, io.ktor.client.engine.cio.CIO.create())
}

fun main() {
    val cfg = AppConfig.fromEnv()
    // The end-to-end CI stack has no Firebase: `test.<uid>` tokens and logged pushes, refused outside HIRZ_ENV=ci.
    val testAuth = System.getenv("HIRZ_TEST_AUTH") == "true"
    if (testAuth) require(System.getenv("HIRZ_ENV") == "ci") { "HIRZ_TEST_AUTH is allowed only with HIRZ_ENV=ci" }
    val firebase = if (testAuth) null else FirebaseTokenVerifier.firebaseApp(
        cfg.firebaseProjectId ?: error("HIRZ_FIREBASE_PROJECT_ID is required: members sign in with Firebase"),
    )
    val deps = Deps(
        db = connectDatabase(cfg),
        verifier = firebase?.let { FirebaseTokenVerifier(it) } ?: dz.hirz.server.auth.CiTokenVerifier,
        notifier = firebase?.let { FcmNotifier(it) } ?: dz.hirz.server.notify.LogNotifier,
        clock = Clock.systemUTC(),
        rules = RuleConfig.DEFAULT,
        dataKey = cfg.dataKey,
        publicBaseUrl = cfg.publicBaseUrl,
        traccarSecret = cfg.traccarSecret,
        traccar = traccarAdmin(cfg),
        deviceHost = cfg.deviceHost,
        claimProofS = cfg.claimProofS,
    )
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) { module(deps) }.start(wait = true)
}
