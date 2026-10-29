package dz.hirz.server

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.escalation.EscalationEngine
import dz.hirz.server.escalation.Ladder
import dz.hirz.server.escalation.LadderEvent
import dz.hirz.server.escalation.Stage
import dz.hirz.server.rules.CheckInScanner
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.GatewayCodeDto
import dz.hirz.shared.api.GatewayPairRequest
import dz.hirz.shared.api.GatewayPairResponse
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.InboundSmsRequest
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.Tier
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private fun engine(env: TestEnv) = EscalationEngine(env.db, env.clock, env.notifier, env.rules, CircleService(env.db, env.clock, CircleEvents.NONE))

private fun stage(env: TestEnv, id: String): String? = env.db.connection.use { c ->
    c.createStatement().executeQuery("SELECT data->>'stage' FROM escalation_state WHERE incident_id = '$id'").use { if (it.next()) it.getString(1) else null }
}

private suspend fun HttpClient.gateway(circleId: String, owner: String = "test.mom"): String {
    val code: GatewayCodeDto = post("/v1/circles/$circleId/gateways") { bearerAuth(owner) }.body()
    return postJson("/v1/gateways/pair", null, GatewayPairRequest(code.code)).body<GatewayPairResponse>().token
}

/** P2 whole-branch review fixes. */
class P2ReviewFixesTest {
    /** Review #1: under duress nothing a guardian taps may reach the carrier's phone. */
    @Test
    fun ringAndCheckinBlockedDuringDuress() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val asset = client.newAsset("test.mom", circle.id)
            val p: PairResponse = client.pair(client.pairingCode("test.mom", asset.id).code, pushToken = "tok-companion").body()
            client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(IngestSignal("d", alarms = setOf(AlarmKind.DURESS)))))
            assertEquals(HttpStatusCode.Conflict, client.post("/v1/assets/${asset.id}/ring") { bearerAuth("test.mom") }.status)
            assertEquals(HttpStatusCode.Conflict, client.post("/v1/assets/${asset.id}/checkin") { bearerAuth("test.mom") }.status)
            assertTrue(env.notifier.sent.none { it.first.token == "tok-companion" })
        }
    }

    /** Review #2: a closed incident whose close never reached the ladder stops at the next tick. */
    @Test
    fun ladderFollowsTheIncidentRow() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
            val asset = client.newAsset("test.mom", circle.id)
            val p: PairResponse = client.pair(client.pairingCode("test.mom", asset.id).code).body()
            client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val id = client.get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
            env.db.connection.use { c -> c.createStatement().executeUpdate("UPDATE incidents SET state = 'closed', closed_at = now() WHERE id = '$id'") }
            env.clock.advance(30.seconds); engine(env).tick()
            assertEquals("CLOSED", stage(env, id))

            // A P0 whose ladder was never created (crash between commit and onOpened) gets one at the next tick.
            val orphan = env.db.connection.use { c ->
                c.createStatement().executeQuery(
                    "INSERT INTO incidents (asset_id, circle_id, rule, tier, opened_at) VALUES ('${asset.id}', '${circle.id}', 'SOS', 'P0', '${Instant.ofEpochMilli(env.clock.millis() - 10_000)}') RETURNING id",
                ).use { it.next(); it.getString(1) }
            }
            engine(env).tick()
            assertEquals("GUARDIANS", stage(env, orphan))
        }
    }

    /** Review #2: an owner in the database means CLAIMED for the ladder, whatever order the events arrived in. */
    @Test
    fun ladderOwnershipMatchesDatabase() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val asset = client.newAsset("test.mom", circle.id)
            val p: PairResponse = client.pair(client.pairingCode("test.mom", asset.id).code).body()
            client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val id = client.get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
            client.post("/v1/incidents/$id/claim") { bearerAuth("test.mom") }
            assertEquals("CLAIMED", stage(env, id))
            // The contest committed but its ladder event was lost: the next tick must not leave a parked, ownerless ladder.
            env.db.connection.use { c -> c.createStatement().executeUpdate("UPDATE incidents SET owner_member = NULL WHERE id = '$id'") }
            env.db.connection.use { c -> c.createStatement().executeUpdate("UPDATE escalation_state SET next_check_at = now() - interval '1 day' WHERE incident_id = '$id'") }
            engine(env).tick()
            assertEquals("GUARDIANS", stage(env, id))
        }
    }

    /** Review #4: the owner revokes a lost gateway; pairing a new one revokes the old one. */
    @Test
    fun gatewayCanBeRevoked() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val first = client.gateway(circle.id)
            val second = client.gateway(circle.id)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/gateways/me/outbox") { bearerAuth(first) }.status, "a new gateway replaces the old")
            assertEquals(HttpStatusCode.OK, client.get("/v1/gateways/me/outbox") { bearerAuth(second) }.status)
            client.join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
            assertEquals(HttpStatusCode.NotFound, client.delete("/v1/circles/${circle.id}/gateways") { bearerAuth("test.dad") }.status)
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/circles/${circle.id}/gateways") { bearerAuth("test.mom") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/gateways/me/outbox") { bearerAuth(second) }.status)
        }
    }

    /** Review #5: SMS from strangers (bank codes, personal messages) are never stored. */
    @Test
    fun unknownSenderBodyNotStored() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val token = client.gateway(circle.id)
            client.postJson("/v1/gateways/me/inbox", token, InboundSmsRequest("+213661000000", "Your bank code is 482913", env.clock.millis()))
            val bodies = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT body FROM inbound_sms").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            assertEquals(listOf(""), bodies)
        }
    }

    /** Minor: a check-in that reached no device is not recorded, so it can never raise NO_RESPONSE_CHECKIN. */
    @Test
    fun undeliveredCheckinRaisesNothing() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val asset = client.newAsset("test.mom", circle.id)
            client.pair(client.pairingCode("test.mom", asset.id).code)
            client.post("/v1/assets/${asset.id}/checkin") { bearerAuth("test.mom") }
            env.clock.advance(6.minutes)
            CheckInScanner(env.db, env.clock, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, CircleService(env.db, env.clock, CircleEvents.NONE)))).scanOnce()
            assertTrue(client.get("/v1/circles/${circle.id}/incidents") { bearerAuth("test.mom") }.body<List<IncidentDto>>().isEmpty())
        }
    }

    /** Minor: a contest from the contacts stage asks the first-ranked guardian again, not the second. */
    @Test
    fun contestFromContactsStartsAtTheTop() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val t = Instant.parse("2026-11-01T08:00:00Z")
        val open = Ladder.open(Tier.P0, listOf(a, b), t, dz.hirz.shared.rules.RuleConfig.DEFAULT)
        val contacts = open.copy(stage = Stage.CONTACTS, index = 2)
        val (st, _) = Ladder.next(contacts, LadderEvent.Contested(b, t.plusSeconds(200)), dz.hirz.shared.rules.RuleConfig.DEFAULT)
        assertEquals(a, st.current)
    }
}
