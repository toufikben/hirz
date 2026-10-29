package dz.hirz.server.escalation

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.sms.GatewaySilenceScanner
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.CloseRequest
import dz.hirz.shared.api.ContactRequest
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.GatewayCodeDto
import dz.hirz.shared.api.GatewayPairRequest
import dz.hirz.shared.api.GatewayPairResponse
import dz.hirz.shared.api.InboundSmsRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.MemberDto
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private val HOME = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(HOME.lat + m / 111_320.0, HOME.lon)

@Serializable
private data class Family(val guardians: List<String> = emptyList(), val contacts: Map<String, String> = emptyMap(), val gateway: Boolean = false, val home: Boolean = false)

/** One step at [t] seconds: sos, fix (north metres from home), tick, claim, contest, close, confirm, reply, gateway_scan. */
@Serializable
private data class Step(val t: Long, val action: String, val who: String = "mom", val north: Double = 0.0, val reason: String = "family", val from: String = "", val body: String = "")

@Serializable
private data class Expect(
    val log: List<String> = emptyList(),
    val logExcludes: List<String> = emptyList(),
    val owner: String? = null,
    val state: String? = null,
    val rule: String? = null,
    val events: List<String> = emptyList(),
    val notices: Map<String, Int> = emptyMap(),
)

@Serializable
private data class Scenario(val name: String, val family: Family, val steps: List<Step>, val expect: Expect)

/**
 * Escalation replay scenarios (P2 Task 14): each JSON file drives the real HTTP API and the ladder with a moving
 * clock, then checks the ladder log (in order), the owner, the state and the notices.
 */
class EscalationScenariosTest {
    private val json = Json { ignoreUnknownKeys = false }

    private fun scenarios(): List<Scenario> =
        File(javaClass.getResource("/escalation")!!.toURI()).listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
            .map { json.decodeFromString(Scenario.serializer(), it.readText()) }

    @Test
    fun allEscalationScenariosHold() {
        val all = scenarios()
        assertTrue(all.size >= 7, "every P2 scenario is present")
        val failures = all.mapNotNull { s -> runCatching { run(s) }.exceptionOrNull()?.let { "${s.name}: ${it.message}" } }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun run(s: Scenario) {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            s.family.guardians.forEach { client.join("test.mom", circle.id, "test.$it", Role.GUARDIAN) }
            (listOf("mom") + s.family.guardians).forEach { m ->
                client.put("/v1/me/push-token") { bearerAuth("test.$m"); contentType(ContentType.Application.Json); setBody(PushTokenDto("tok-$m")) }
            }
            val names = mutableMapOf<String, String>()
            s.family.contacts.forEach { (name, phone) ->
                names[client.postJson("/v1/circles/${circle.id}/contacts", "test.mom", ContactRequest(name, phone)).body<MemberDto>().memberId] = name
            }
            val gateway = if (s.family.gateway) {
                val code: GatewayCodeDto = client.post("/v1/circles/${circle.id}/gateways") { bearerAuth("test.mom") }.body()
                client.postJson("/v1/gateways/pair", null, GatewayPairRequest(code.code, pushToken = "tok-gateway")).body<GatewayPairResponse>().token
            } else null
            if (s.family.home) client.postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Home", PlaceKind.HOME, ShapeDto.Circle(HOME, 80.0)))
            val asset = client.newAsset("test.mom", circle.id)
            val paired: PairResponse = client.pair(client.pairingCode("test.mom", asset.id).code).body()
            client.get("/v1/circles/${circle.id}/members") { bearerAuth("test.mom") }.body<List<MemberDto>>().forEach { m ->
                if (m.memberId !in names) names[m.memberId] = when (m.role) { Role.OWNER -> "mom"; else -> m.displayName ?: m.memberId }
            }

            val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
            val engine = EscalationEngine(env.db, env.clock, env.notifier, env.rules, circles).also { it.ordering = NearestOrdering(env.rules) }
            val incidents = IncidentService(env.db, env.clock, env.notifier, circles, env.rules)
            val silence = GatewaySilenceScanner(env.db, env.clock, env.notifier)
            val start = env.clock.now
            var n = 0
            suspend fun incidentId(): String = client.get("/v1/circles/${circle.id}/incidents") { bearerAuth("test.mom") }
                .body<List<IncidentDto>>().firstOrNull()?.id ?: error("no incident at step $n")

            for (step in s.steps) {
                n++
                val at: Instant = start.plusSeconds(step.t)
                require(!at.isBefore(env.clock.now)) { "steps must be in time order" }
                env.clock.now = at
                val who = "test.${step.who}"
                when (step.action) {
                    "sos" -> client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos$n", alarms = setOf(AlarmKind.SOS)))))
                    "fix" -> client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("fix$n", env.clock.millis(), north(step.north), 10.0))))
                    "tick" -> { engine.tick(); incidents.expireCloseRequests() }
                    "claim" -> client.post("/v1/incidents/${incidentId()}/claim") { bearerAuth(who) }
                    "contest" -> client.post("/v1/incidents/${incidentId()}/contest") { bearerAuth(who) }
                    "close" -> client.postJson("/v1/incidents/${incidentId()}/close", who, CloseRequest(CloseReason.entries.single { it.name.lowercase() == step.reason }))
                    "confirm" -> client.post("/v1/incidents/${incidentId()}/close/confirm") { bearerAuth(who) }
                    "reply" -> client.postJson("/v1/gateways/me/inbox", gateway ?: error("no gateway"), InboundSmsRequest(step.from, step.body, env.clock.millis()))
                    "gateway_scan" -> silence.scanOnce()
                    else -> error("unknown action ${step.action}")
                }
            }

            val log = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT kind FROM escalation_log ORDER BY id").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            var i = 0
            s.expect.log.forEach { kind -> i = log.subList(i, log.size).indexOf(kind).let { if (it < 0) error("log lacks '$kind' after position $i: $log") else i + it + 1 } }
            s.expect.logExcludes.forEach { if (it in log) error("log must not contain '$it': $log") }
            val list = client.get("/v1/circles/${circle.id}/incidents") { bearerAuth("test.mom") }.body<List<IncidentDto>>()
            if (s.expect.state != null || s.expect.owner != null || s.expect.rule != null || s.expect.events.isNotEmpty()) {
                val inc = list.firstOrNull() ?: error("no incident")
                s.expect.state?.let { assertEquals(it, inc.state, "state") }
                s.expect.rule?.let { assertEquals(it, inc.rule.name, "rule") }
                s.expect.owner?.let { assertEquals(it, inc.ownerMemberId?.let { id -> names[id] ?: id } ?: "none", "owner") }
                val detail: IncidentDto = client.get("/v1/incidents/${inc.id}") { bearerAuth("test.mom") }.body()
                s.expect.events.forEach { e -> if (detail.events.none { it.kind == e }) error("events lack '$e': ${detail.events.map { it.kind }}") }
            }
            s.expect.notices.forEach { (kind, count) -> assertEquals(count, env.notifier.sent.count { it.second.kind == kind }, "notices $kind") }
        }
    }
}
