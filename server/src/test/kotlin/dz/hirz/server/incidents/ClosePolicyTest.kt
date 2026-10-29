package dz.hirz.server.incidents

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.ActionRequest
import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.CloseRequest
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.ReviewRequest
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val HOME = LatLon(36.7538, 3.0588)

private suspend fun HttpClient.setup(guardians: List<String>, env: TestEnv): Triple<String, String, PairResponse> {
    val circle = newCircle("test.mom")
    guardians.forEach { join("test.mom", circle.id, it, Role.GUARDIAN) }
    join("test.mom", circle.id, "test.aunt", Role.VIEWER)
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Home", PlaceKind.HOME, ShapeDto.Circle(HOME, 80.0)))
    val asset = newAsset("test.mom", circle.id)
    val paired: PairResponse = pair(pairingCode("test.mom", asset.id).code).body()
    return Triple(circle.id, asset.id, paired)
}

private suspend fun HttpClient.sosIncident(circleId: String, paired: PairResponse, alarms: Set<AlarmKind> = setOf(AlarmKind.SOS)): String {
    postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("s${alarms.hashCode()}", alarms = alarms))))
    return get("/v1/circles/$circleId/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().first().id
}

private suspend fun HttpClient.incident(id: String): IncidentDto = get("/v1/incidents/$id") { bearerAuth("test.mom") }.body()

class ClosePolicyTest {
    @Test
    fun p0CloseNeedsSecondGuardian() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.setup(listOf("test.dad"), env)
            val id = client.sosIncident(circleId, paired)
            val requested: IncidentDto = client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FAMILY)).body()
            assertEquals("open", requested.state)
            assertNotNull(requested.pendingClose)
            assertEquals(HttpStatusCode.OK, client.post("/v1/incidents/$id/close/confirm") { bearerAuth("test.dad") }.status)
            assertEquals("closed", client.incident(id).state)
        }
    }

    @Test
    fun sameGuardianCannotConfirmWhenTwoExist() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.setup(listOf("test.dad"), env)
            val id = client.sosIncident(circleId, paired)
            client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FAMILY))
            assertEquals(HttpStatusCode.Conflict, client.post("/v1/incidents/$id/close/confirm") { bearerAuth("test.mom") }.status)
            assertEquals("open", client.incident(id).state)
        }
    }

    @Test
    fun singleGuardianCloseNeedsTimedConfirm() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.setup(emptyList(), env)
            val id = client.sosIncident(circleId, paired)
            client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FAMILY))
            assertEquals("open", client.incident(id).state)
            env.clock.advance(30.seconds)
            assertEquals(HttpStatusCode.OK, client.post("/v1/incidents/$id/close/confirm") { bearerAuth("test.mom") }.status)
            assertEquals("closed", client.incident(id).state)
        }
    }

    @Test
    fun closeRequestExpiresAndIncidentStaysOpen() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.setup(emptyList(), env)
            val id = client.sosIncident(circleId, paired)
            client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FAMILY))
            env.clock.advance(121.seconds)
            assertEquals(HttpStatusCode.Gone, client.post("/v1/incidents/$id/close/confirm") { bearerAuth("test.mom") }.status)
            val after = client.incident(id)
            assertEquals("open", after.state)
            assertEquals(null, after.pendingClose)
            assertTrue(after.events.any { it.kind == "close_request_expired" })
        }
    }

    @Test
    fun foundSafeWithoutProofNeedsQuorumEvenForP1() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, assetId, _) = client.setup(listOf("test.dad"), env)
            val id = env.db.connection.use { c ->
                c.createStatement().executeQuery(
                    "INSERT INTO incidents (asset_id, circle_id, rule, tier, opened_at) VALUES ('$assetId', '$circleId', 'PLACE_EXIT', 'P1', now()) RETURNING id",
                ).use { it.next(); it.getString(1) }
            }
            val r: IncidentDto = client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FOUND_SAFE)).body()
            assertEquals("open", r.state)
            assertNotNull(r.pendingClose)
        }
    }

    @Test
    fun foundSafeWithAssetHomeIsAllowed() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, assetId, paired) = client.setup(listOf("test.dad"), env)
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("home", env.clock.millis(), HOME, 10.0))))
            val id = env.db.connection.use { c ->
                c.createStatement().executeQuery(
                    "INSERT INTO incidents (asset_id, circle_id, rule, tier, opened_at) VALUES ('$assetId', '$circleId', 'PLACE_EXIT', 'P1', now()) RETURNING id",
                ).use { it.next(); it.getString(1) }
            }
            val r: IncidentDto = client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FOUND_SAFE)).body()
            assertEquals("closed", r.state)
        }
    }

    @Test
    fun viewerCannotClaimButCanContest() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.setup(listOf("test.dad"), env)
            val id = client.sosIncident(circleId, paired)
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/incidents/$id/claim") { bearerAuth("test.aunt") }.status)
            client.post("/v1/incidents/$id/claim") { bearerAuth("test.dad") }
            assertEquals(HttpStatusCode.OK, client.post("/v1/incidents/$id/contest") { bearerAuth("test.aunt") }.status)
        }
    }

    @Test
    fun duplicateCallIsReportedWithWhoDidIt() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.setup(listOf("test.dad"), env)
            val id = client.sosIncident(circleId, paired)
            assertEquals(HttpStatusCode.OK, client.postJson("/v1/incidents/$id/actions", "test.dad", ActionRequest("called_number", "1548")).status)
            val again = client.postJson("/v1/incidents/$id/actions", "test.mom", ActionRequest("called_number", "1548"))
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertTrue("already_done" in again.bodyAsText())
            assertTrue(client.incident(id).events.any { it.kind == "action" && it.data["value"].toString().contains("1548") })
            env.clock.advance(601.seconds)
            assertEquals(HttpStatusCode.OK, client.postJson("/v1/incidents/$id/actions", "test.mom", ActionRequest("called_number", "1548")).status)
        }
    }

    @Test
    fun reviewFeedsFalseAlarmCounter() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.setup(listOf("test.dad"), env)
            val id = client.sosIncident(circleId, paired)
            client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FAMILY))
            client.post("/v1/incidents/$id/close/confirm") { bearerAuth("test.dad") }
            assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/incidents/$id/review", "test.dad", ReviewRequest(falseAlarm = true, note = "pocket press")).status)
            val counts: Map<String, Int> = client.get("/v1/circles/$circleId/false-alarms") { bearerAuth("test.mom") }.body()
            assertEquals(1, counts["SOS"])
        }
    }
}
