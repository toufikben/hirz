package dz.hirz.server.custody

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.CloseRequest
import dz.hirz.shared.api.ConsentDto
import dz.hirz.shared.api.ConsentRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LinkCodeDto
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.signal.AlarmKind
import dz.hirz.server.support.testJson
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.LiveEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlin.test.assertIs
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private suspend fun DefaultClientWebSocketSession.nextEvent(): LiveEvent = withTimeout(5_000) {
    var event: LiveEvent? = null
    while (event == null) {
        val frame = incoming.receive()
        if (frame is Frame.Text) event = testJson.decodeFromString(LiveEvent.serializer(), frame.readText())
    }
    event
}

private class Shared(val circleId: String, val otherId: String, val consentId: String, val sourceToken: String)

/** mom's circle owns the child; father2's circle shares custody (active consent). Both have push tokens. */
private suspend fun HttpClient.sharedFamily(): Shared {
    val mine = newCircle("test.mom")
    val other = newCircle("test.father2")
    listOf("test.mom" to "tok-mom", "test.father2" to "tok-father2").forEach { (m, t) ->
        put("/v1/me/push-token") { bearerAuth(m); contentType(ContentType.Application.Json); setBody(PushTokenDto(t)) }
    }
    val asset = newAsset("test.mom", mine.id)
    val paired: PairResponse = pair(pairingCode("test.mom", asset.id).code).body()
    val code: LinkCodeDto = post("/v1/circles/${other.id}/link-code") { bearerAuth("test.father2") }.body()
    val consent: ConsentDto = postJson("/v1/assets/${asset.id}/consents", "test.mom", ConsentRequest(code.code)).body()
    post("/v1/consents/${consent.id}/accept") { bearerAuth("test.father2") }
    return Shared(mine.id, other.id, consent.id, paired.sourceToken)
}

private suspend fun HttpClient.sos(s: Shared) =
    postJson("/v1/ingest", s.sourceToken, IngestBatch(listOf(IngestSignal("sos-${System.nanoTime()}", alarms = setOf(AlarmKind.SOS)))))

class SharedCustodyAlertsTest {
    @Test
    fun sharedCircleGetsTheSosAlert() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.sharedFamily()
            client.sos(s)
            assertEquals(setOf("tok-mom", "tok-father2"), env.notifier.alerts.map { it.first.token }.toSet())
            val seen = client.get("/v1/circles/${s.otherId}/incidents?state=open") { bearerAuth("test.father2") }.body<List<IncidentDto>>()
            assertEquals(1, seen.size, "the other parent sees the shared child's incident")
            assertEquals(HttpStatusCode.OK, client.get("/v1/incidents/${seen.single().id}") { bearerAuth("test.father2") }.status)
        }
    }

    @Test
    fun revokedConsentStopsAlerts() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.sharedFamily()
            client.delete("/v1/consents/${s.consentId}") { bearerAuth("test.mom") }
            client.sos(s)
            assertEquals(setOf("tok-mom"), env.notifier.alerts.map { it.first.token }.toSet())
            assertTrue(client.get("/v1/circles/${s.otherId}/incidents?state=open") { bearerAuth("test.father2") }.body<List<IncidentDto>>().isEmpty())
        }
    }

    @Test
    fun sharedGuardianCanClaimButNotClose() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.sharedFamily()
            client.sos(s)
            val id = client.get("/v1/circles/${s.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
            assertEquals(HttpStatusCode.OK, client.post("/v1/incidents/$id/claim") { bearerAuth("test.father2") }.status)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/incidents/$id/close", "test.father2", CloseRequest(CloseReason.FAMILY)).status)
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/incidents/$id/police-pack") { bearerAuth("test.father2") }.status)
        }
    }

    @Test
    fun liveSnapshotIncludesSharedAsset() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.sharedFamily()
            client.webSocket("/v1/live?token=test.father2") {
                val snapshot = assertIs<LiveEvent.Snapshot>(nextEvent())
                assertEquals(1, snapshot.assets.size, "the shared child is on the other parent's map")
                client.postJson("/v1/ingest", s.sourceToken, IngestBatch(listOf(IngestSignal("fix", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
                val update = assertIs<LiveEvent.PositionUpdate>(nextEvent())
                assertEquals(LatLon(36.75, 3.05), update.position.position)
            }
        }
    }
}
