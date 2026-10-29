package dz.hirz.server.incidents

import dz.hirz.server.auth.Codes
import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.rules.CheckInScanner
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CheckInReply
import dz.hirz.shared.api.CheckInSentDto
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.CloseRequest
import dz.hirz.shared.api.CreatePairingRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private class Child(val circleId: String, val assetId: String, val paired: PairResponse)

/** mom (owner) and aunt (viewer); a child paired with push token `tok-companion` and cancel PIN 1234. */
private suspend fun HttpClient.child(): Child {
    val circle = newCircle("test.mom")
    join("test.mom", circle.id, "test.aunt", Role.VIEWER)
    val asset = newAsset("test.mom", circle.id)
    val code = pairingCode("test.mom", asset.id, CreatePairingRequest(cancelPin = "1234", duressPin = "9876")).code
    return Child(circle.id, asset.id, pair(code, pushToken = "tok-companion").body())
}

private suspend fun HttpClient.openIncidents(circleId: String): List<IncidentDto> =
    get("/v1/circles/$circleId/incidents?state=open") { bearerAuth("test.mom") }.body()

private fun scanner(env: TestEnv) =
    CheckInScanner(env.db, env.clock, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, CircleService(env.db, env.clock, CircleEvents.NONE))))

private fun TestEnv.sentTo(token: String, kind: String) = notifier.sent.count { it.first.token == token && it.second.kind == kind }

class RemoteActionsTest {
    @Test
    fun ringPushesToSources() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val ch = client.child()
            val r = client.post("/v1/assets/${ch.assetId}/ring") { bearerAuth("test.mom") }
            assertEquals(1, r.body<CheckInSentDto>().sent)
            assertEquals(1, env.sentTo("tok-companion", "ring"))
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/assets/${ch.assetId}/ring") { bearerAuth("test.aunt") }.status)
        }
    }

    @Test
    fun p0OpensWithHighRateToSources() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val ch = client.child()
            client.postJson("/v1/ingest", ch.paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            assertEquals(1, env.sentTo("tok-companion", "high_rate"))
            assertEquals(0, env.sentTo("tok-companion", "incident"), "the carrier's own phone never gets the alert")
        }
    }

    @Test
    fun closeSendsNormalRate() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val ch = client.child()
            client.postJson("/v1/ingest", ch.paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val id = client.openIncidents(ch.circleId).single().id
            client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FAMILY))
            assertEquals(0, env.sentTo("tok-companion", "normal_rate"), "a close request alone changes nothing")
            client.post("/v1/incidents/$id/close/confirm") { bearerAuth("test.mom") }
            assertEquals(1, env.sentTo("tok-companion", "normal_rate"))
        }
    }

    @Test
    fun unansweredCheckinAfter5MinIsP1() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val ch = client.child()
            client.post("/v1/assets/${ch.assetId}/checkin") { bearerAuth("test.mom") }
            env.clock.advance(299.seconds); scanner(env).scanOnce()
            assertTrue(client.openIncidents(ch.circleId).isEmpty())
            env.clock.advance(2.seconds); scanner(env).scanOnce()
            val incident = client.openIncidents(ch.circleId).single()
            assertEquals(RuleId.NO_RESPONSE_CHECKIN, incident.rule)
            assertEquals(Tier.P1, incident.tier)
            assertEquals("no_checkin_reply", incident.reasons.single().code)
            scanner(env).scanOnce()
            assertEquals(1, client.openIncidents(ch.circleId).size, "one finding per check-in")
        }
    }

    @Test
    fun answeredCheckinRaisesNothing() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val ch = client.child()
            client.post("/v1/assets/${ch.assetId}/checkin") { bearerAuth("test.mom") }
            env.clock.advance(30.seconds)
            client.postJson("/v1/ingest", ch.paired.sourceToken, IngestBatch(listOf(IngestSignal("r", checkInReply = CheckInReply.OK))))
            env.clock.advance(600.seconds); scanner(env).scanOnce()
            assertTrue(client.openIncidents(ch.circleId).isEmpty())
        }
    }

    @Test
    fun cancelPinAddsReasonNotClose() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val ch = client.child()
            client.postJson("/v1/ingest", ch.paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            client.postJson("/v1/ingest", ch.paired.sourceToken, IngestBatch(listOf(IngestSignal("pin", pinHash = Codes.pinHash(ch.paired.pinSalt!!, "1234")))))
            val incident = client.openIncidents(ch.circleId).single()
            assertEquals("open", incident.state)
            assertTrue(incident.reasons.any { it.code == "cancel_pin_entered" })
        }
    }
}
