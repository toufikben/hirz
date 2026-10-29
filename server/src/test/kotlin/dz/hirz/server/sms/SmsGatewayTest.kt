package dz.hirz.server.sms

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.escalation.EscalationEngine
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.CloseRequest
import dz.hirz.shared.api.ContactRequest
import dz.hirz.shared.api.GatewayCodeDto
import dz.hirz.shared.api.GatewayPairRequest
import dz.hirz.shared.api.GatewayPairResponse
import dz.hirz.shared.api.InboundSmsRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.MemberDto
import dz.hirz.shared.api.OutboxItemDto
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.SmsResultRequest
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private class Family(val circleId: String, val incidentId: String, val gatewayToken: String, val contactId: String)

/**
 * mom alone with a paired child; one contact (Jaddah, 0550123456); a paired SMS gateway; an SOS whose ladder
 * reached the contacts stage, so the outbox holds one SMS.
 */
private suspend fun HttpClient.family(env: TestEnv, owner: String = "test.mom", phone: String = "0550123456"): Family {
    val circle = newCircle(owner)
    val contact: MemberDto = postJson("/v1/circles/${circle.id}/contacts", owner, ContactRequest("Jaddah", phone)).body()
    val code: GatewayCodeDto = post("/v1/circles/${circle.id}/gateways") { bearerAuth(owner) }.body()
    val gw: GatewayPairResponse = postJson("/v1/gateways/pair", null, GatewayPairRequest(code.code, pushToken = "tok-gw-$owner")).body()
    val asset = newAsset(owner, circle.id)
    val paired: PairResponse = pair(pairingCode(owner, asset.id).code).body()
    postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
    val id = get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth(owner) }.body<List<IncidentDto>>().single().id
    return Family(circle.id, id, gw.token, contact.memberId)
}

private fun engine(env: TestEnv) = EscalationEngine(env.db, env.clock, env.notifier, env.rules, CircleService(env.db, env.clock, CircleEvents.NONE))

/** Moves every open ladder past the only guardian to the contacts stage. */
private fun toContacts(env: TestEnv) {
    val e = engine(env)
    env.clock.advance(20.seconds); e.tick()
    env.clock.advance(61.seconds); e.tick()
}

private suspend fun HttpClient.outbox(token: String): List<OutboxItemDto> = get("/v1/gateways/me/outbox") { bearerAuth(token) }.body()

private suspend fun HttpClient.reply(token: String, from: String, body: String, env: TestEnv) =
    postJson("/v1/gateways/me/inbox", token, InboundSmsRequest(from, body, env.clock.millis()))

private fun stage(env: TestEnv, incidentId: String): String = env.db.connection.use { c ->
    c.createStatement().executeQuery("SELECT data->>'stage' FROM escalation_state WHERE incident_id = '$incidentId'").use { it.next(); it.getString(1) }
}

class SmsGatewayTest {
    @Test
    fun contactsStageQueuesOneSmsPerContact() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family(env)
            toContacts(env)
            val items = client.outbox(f.gatewayToken)
            assertEquals(1, items.size)
            assertEquals("+213550123456", items.single().to)
            assertTrue(env.notifier.sent.any { it.first.token == "tok-gw-test.mom" && it.second.kind == "sms_pending" }, "the gateway is nudged")
            assertTrue(client.outbox(f.gatewayToken).isEmpty(), "a taken item is not offered again at once")
            val r = client.postJson("/v1/gateways/me/outbox/${items.single().id}/result", f.gatewayToken, SmsResultRequest("sent"))
            assertEquals(HttpStatusCode.NoContent, r.status)
            env.clock.advance(120.seconds)
            assertTrue(client.outbox(f.gatewayToken).isEmpty(), "a sent item is never offered again")
        }
    }

    @Test
    fun replyOneClaimsForContact() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family(env)
            toContacts(env)
            assertEquals(HttpStatusCode.NoContent, client.reply(f.gatewayToken, "+213550123456", " 1 ", env).status)
            val incident: IncidentDto = client.get("/v1/incidents/${f.incidentId}") { bearerAuth("test.mom") }.body()
            assertEquals(f.contactId, incident.ownerMemberId)
            assertTrue(incident.events.any { it.kind == "claimed" && it.data["via"].toString().contains("sms") })
            assertEquals("CLAIMED", stage(env, f.incidentId))
        }
    }

    @Test
    fun replyTwoEscalatesFurther() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family(env)
            toContacts(env)
            assertEquals("CONTACTS", stage(env, f.incidentId))
            client.reply(f.gatewayToken, "0550123456", "2", env)
            assertEquals("EVERYONE_UNAVAILABLE", stage(env, f.incidentId), "the only contact declined, so nobody is left")
            val incident: IncidentDto = client.get("/v1/incidents/${f.incidentId}") { bearerAuth("test.mom") }.body()
            assertNull(incident.ownerMemberId)
        }
    }

    @Test
    fun unknownNumberReplyIgnored() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family(env)
            toContacts(env)
            assertEquals(HttpStatusCode.NoContent, client.reply(f.gatewayToken, "+213661000000", "1", env).status)
            val incident: IncidentDto = client.get("/v1/incidents/${f.incidentId}") { bearerAuth("test.mom") }.body()
            assertNull(incident.ownerMemberId)
            env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*), bool_and(action = 'unknown_sender') FROM inbound_sms").use {
                    it.next(); assertEquals(1, it.getInt(1)); assertTrue(it.getBoolean(2))
                }
            }
        }
    }

    @Test
    fun replyToClosedIncidentIgnored() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family(env)
            toContacts(env)
            client.postJson("/v1/incidents/${f.incidentId}/close", "test.mom", CloseRequest(CloseReason.FAMILY))
            client.post("/v1/incidents/${f.incidentId}/close/confirm") { bearerAuth("test.mom") }
            assertEquals(HttpStatusCode.NoContent, client.reply(f.gatewayToken, "+213550123456", "1", env).status)
            val incident: IncidentDto = client.get("/v1/incidents/${f.incidentId}") { bearerAuth("test.mom") }.body()
            assertEquals("closed", incident.state)
            assertNull(incident.ownerMemberId)
        }
    }

    @Test
    fun arabicIndicDigitsAccepted() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family(env)
            toContacts(env)
            client.reply(f.gatewayToken, "+213550123456", "١", env)
            val incident: IncidentDto = client.get("/v1/incidents/${f.incidentId}") { bearerAuth("test.mom") }.body()
            assertEquals(f.contactId, incident.ownerMemberId)
        }
    }

    @Test
    fun smsTextFitsTwoPartsAndHasNoCoordinates() {
        val longName = "عبد الرحمن بن محمد الأمين الجزائري الطويل جدا"
        for (lang in listOf("ar", "fr", "en")) {
            for (tier in listOf("P0", "P1")) {
                val text = SmsText.alert(lang, longName, tier, "H-4821")
                assertTrue(SmsText.parts(text) <= 2, "$lang $tier: ${SmsText.parts(text)} parts")
                assertFalse(Regex("""\d+\.\d{3,}""").containsMatchIn(text), "no coordinates")
                assertTrue("H-4821" in text)
                assertTrue("1" in text && "2" in text)
            }
        }
        assertEquals(1, SmsText.parts("a".repeat(160)))
        assertEquals(2, SmsText.parts("a".repeat(161)))
        assertEquals(1, SmsText.parts("ب".repeat(70)))
        assertEquals(2, SmsText.parts("ب".repeat(71)))
    }

    @Test
    fun gatewayTokenCannotReadOtherCircles() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val a = client.family(env, "test.mom", "0550123456")
            val b = client.family(env, "test.other", "0770999888")
            toContacts(env)
            val forA = client.outbox(a.gatewayToken)
            val forB = client.outbox(b.gatewayToken)
            assertEquals(listOf("+213550123456"), forA.map { it.to })
            assertEquals(listOf("+213770999888"), forB.map { it.to })
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/gateways/me/outbox/${forB.single().id}/result", a.gatewayToken, SmsResultRequest("sent")).status)
            client.reply(a.gatewayToken, "+213770999888", "1", env)
            val incidentB: IncidentDto = client.get("/v1/incidents/${b.incidentId}") { bearerAuth("test.other") }.body()
            assertNull(incidentB.ownerMemberId, "a reply through another circle's gateway does nothing")
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/gateways/me/outbox") { bearerAuth("test.mom") }.status)
        }
    }
}
