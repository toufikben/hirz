package dz.hirz.server.incidents

import dz.hirz.server.jobs.SilenceScanner
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.support.FakeNotifier
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CheckInSentDto
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.CloseRequest
import dz.hirz.shared.api.CreatePairingRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.MeDto
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.ReceiptRequest
import dz.hirz.shared.api.ReceiptState
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.Tier
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val MEMBERS = listOf("test.owner", "test.mom", "test.grandpa", "test.aunt")

/** A family of four (each with a push token "tok-<name>") and a paired child; returns (circleId, assetId, pair). */
private suspend fun HttpClient.family(withTokens: List<String> = MEMBERS, pairReq: CreatePairingRequest = CreatePairingRequest()): Triple<String, String, PairResponse> {
    val circle = newCircle("test.owner")
    join("test.owner", circle.id, "test.mom", Role.GUARDIAN)
    join("test.owner", circle.id, "test.grandpa", Role.GUARDIAN)
    join("test.owner", circle.id, "test.aunt", Role.VIEWER)
    withTokens.forEach { m ->
        put("/v1/me/push-token") { bearerAuth(m); contentType(ContentType.Application.Json); setBody(PushTokenDto("tok-${m.removePrefix("test.")}")) }
    }
    val asset = newAsset("test.owner", circle.id)
    val paired: PairResponse = pair(pairingCode("test.owner", asset.id, pairReq).code, pushToken = "tok-companion").body()
    return Triple(circle.id, asset.id, paired)
}

private suspend fun HttpClient.sos(token: String, id: String, alarms: Set<AlarmKind> = setOf(AlarmKind.SOS)) =
    postJson("/v1/ingest", token, IngestBatch(listOf(IngestSignal(clientId = id, alarms = alarms))))

private suspend fun HttpClient.openIncidents(member: String, circleId: String): List<IncidentDto> =
    get("/v1/circles/$circleId/incidents?state=open") { bearerAuth(member) }.body()

private suspend fun HttpClient.incident(member: String, id: String): IncidentDto = get("/v1/incidents/$id") { bearerAuth(member) }.body()

class IncidentTest {
    @Test
    fun p0FansOutToAllMembersInParallel() {
        val env = TestEnv(notifier = FakeNotifier(delay = 500.milliseconds))
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.family()
            val started = System.nanoTime()
            client.sos(paired.sourceToken, "sos1")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 1_500, "fan-out took $elapsedMs ms; four sequential sends would take ≥ 2000 ms")
            assertEquals(MEMBERS.map { "tok-${it.removePrefix("test.")}" }.toSet(), env.notifier.sent.map { it.first.token }.toSet())
            val incident = client.openIncidents("test.mom", circleId).single()
            assertEquals(Tier.P0, incident.tier)
            assertEquals(4, client.incident("test.mom", incident.id).deliveries.count { it.sentAtMs != null })
        }
    }

    @Test
    fun fanout_continues_when_one_token_fails() {
        val env = TestEnv(notifier = FakeNotifier(failTokens = setOf("tok-mom")))
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.family()
            client.sos(paired.sourceToken, "sos1")
            assertEquals(3, env.notifier.sent.size)
            val deliveries = client.incident("test.owner", client.openIncidents("test.owner", circleId).single().id).deliveries
            assertEquals(1, deliveries.count { it.error != null })
            env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM push_tokens WHERE token = 'tok-mom'").use { it.next(); assertEquals(0, it.getInt(1)) }
            }
        }
    }

    @Test
    fun memberWithoutTokenIsRecordedNotSkipped() = withApp { client, env ->
        val (circleId, _, paired) = client.family(withTokens = listOf("test.owner"))
        client.sos(paired.sourceToken, "sos1")
        assertEquals(1, env.notifier.sent.size)
        val deliveries = client.incident("test.owner", client.openIncidents("test.owner", circleId).single().id).deliveries
        assertEquals(3, deliveries.count { it.error == "no_push_token" })
    }

    @Test
    fun p2IsNotPushed() = withApp { client, env ->
        val (circleId, _, paired) = client.family()
        client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("fix", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
        env.clock.advance(6.minutes)
        val sink = IncidentService(env.db, env.clock, env.notifier, dz.hirz.server.circles.CircleService(env.db, env.clock, dz.hirz.server.circles.CircleEvents.NONE))
        SilenceScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, sink)).scanOnce()
        val incident = client.openIncidents("test.owner", circleId).single()
        assertEquals(Tier.P2, incident.tier)
        assertTrue(env.notifier.sent.isEmpty())
    }

    @Test
    fun duplicateFindingAppendsNotReopens() = withApp { client, env ->
        val (circleId, _, paired) = client.family()
        client.sos(paired.sourceToken, "sos1")
        client.sos(paired.sourceToken, "sos2")
        val incident = client.openIncidents("test.owner", circleId).single()
        assertEquals(4, env.notifier.sent.size, "the second SOS joins the open incident without re-alerting")
        assertTrue(client.incident("test.owner", incident.id).events.count { it.kind == "finding" } >= 1)
    }

    @Test
    fun claimDoesNotCloseAndIsIdempotent() = withApp { client, _ ->
        val (circleId, _, paired) = client.family()
        client.sos(paired.sourceToken, "sos1")
        val id = client.openIncidents("test.mom", circleId).single().id
        assertEquals(HttpStatusCode.OK, client.post("/v1/incidents/$id/claim") { bearerAuth("test.mom") }.status)
        client.post("/v1/incidents/$id/claim") { bearerAuth("test.mom") }
        val claimed = client.incident("test.owner", id)
        val mom: MeDto = client.get("/v1/me") { bearerAuth("test.mom") }.body()
        assertEquals(mom.memberId, claimed.ownerMemberId)
        assertEquals("open", claimed.state)
        assertEquals(Tier.P0, claimed.tier)
        assertEquals(1, claimed.events.count { it.kind == "claimed" })
        // someone else claiming does not steal it
        client.post("/v1/incidents/$id/claim") { bearerAuth("test.grandpa") }
        assertEquals(mom.memberId, client.incident("test.owner", id).ownerMemberId)
    }

    @Test
    fun contestClearsOwner() = withApp { client, _ ->
        val (circleId, _, paired) = client.family()
        client.sos(paired.sourceToken, "sos1")
        val id = client.openIncidents("test.mom", circleId).single().id
        client.post("/v1/incidents/$id/claim") { bearerAuth("test.mom") }
        assertEquals(HttpStatusCode.OK, client.post("/v1/incidents/$id/contest") { bearerAuth("test.grandpa") }.status)
        val after = client.incident("test.owner", id)
        assertNull(after.ownerMemberId)
        assertEquals(1, after.events.count { it.kind == "contested" })
    }

    @Test
    fun closeRecordsReasonOnce() = withApp { client, _ ->
        val (circleId, _, paired) = client.family()
        client.sos(paired.sourceToken, "sos1")
        val id = client.openIncidents("test.mom", circleId).single().id
        assertEquals(HttpStatusCode.OK, client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FOUND_SAFE)).status)
        assertEquals(CloseReason.FOUND_SAFE, client.incident("test.owner", id).closeReason)
        assertEquals(HttpStatusCode.Conflict, client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FAMILY)).status)
        assertTrue(client.openIncidents("test.owner", circleId).isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/incidents/$id/close", "test.aunt", CloseRequest(CloseReason.FAMILY)).status)
    }

    @Test
    fun receiptsAreMonotonic() = withApp { client, _ ->
        val (circleId, _, paired) = client.family()
        client.sos(paired.sourceToken, "sos1")
        val id = client.openIncidents("test.mom", circleId).single().id
        client.postJson("/v1/incidents/$id/receipts", "test.mom", ReceiptRequest(ReceiptState.OPENED))
        client.postJson("/v1/incidents/$id/receipts", "test.mom", ReceiptRequest(ReceiptState.DELIVERED))
        val first = client.incident("test.owner", id).deliveries.single { it.channel == "fcm" && it.openedAtMs != null }
        client.postJson("/v1/incidents/$id/receipts", "test.mom", ReceiptRequest(ReceiptState.DELIVERED))
        val second = client.incident("test.owner", id).deliveries.single { it.memberId == first.memberId }
        assertEquals(first.deliveredAtMs, second.deliveredAtMs)
        assertEquals(first.openedAtMs, second.openedAtMs)
    }

    @Test
    fun duressNeverNotifiesCarrier() = withApp { client, env ->
        val (circleId, _, paired) = client.family()
        client.sos(paired.sourceToken, "d1", setOf(AlarmKind.DURESS))
        assertTrue(env.notifier.sent.none { it.first.token == "tok-companion" })
        assertEquals(4, env.notifier.sent.size)
        assertTrue(client.openIncidents("test.owner", circleId).single().silent)
    }

    @Test
    fun strangerCannotSeeIncident() = withApp { client, _ ->
        val (circleId, _, paired) = client.family()
        client.sos(paired.sourceToken, "sos1")
        val id = client.openIncidents("test.owner", circleId).single().id
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/incidents/$id") { bearerAuth("test.stranger") }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/incidents/$id/claim") { bearerAuth("test.stranger") }.status)
    }

    @Test
    fun checkinPushesToAssetSources() = withApp { client, env ->
        val (_, assetId, _) = client.family()
        val result: CheckInSentDto = client.post("/v1/assets/$assetId/checkin") { bearerAuth("test.mom") }.body()
        assertEquals(1, result.sent)
        val (target, message) = env.notifier.sent.single()
        assertEquals("tok-companion", target.token)
        assertEquals("checkin", message.kind)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/assets/$assetId/checkin") { bearerAuth("test.aunt") }.status)
    }
}
