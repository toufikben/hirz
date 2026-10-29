package dz.hirz.server.escalation

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
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
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.MemberDto
import dz.hirz.shared.api.MyLocationRequest
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.Role
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

private val CHILD = LatLon(36.7538, 3.0588)
private fun near(m: Double) = LatLon(CHILD.lat + m / 111_320.0, CHILD.lon)

/** mom (owner, priority 1), uncle (priority 2), dad (priority 3); child paired and sending a fix. */
private suspend fun HttpClient.family(env: TestEnv): Triple<String, String, PairResponse> {
    val circle = newCircle("test.mom")
    join("test.mom", circle.id, "test.uncle", Role.GUARDIAN)
    join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
    val asset = newAsset("test.mom", circle.id)
    val paired: PairResponse = pair(pairingCode("test.mom", asset.id).code).body()
    postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("fix", env.clock.millis(), CHILD, 10.0))))
    return Triple(circle.id, asset.id, paired)
}

class MemberLocationTest {
    private fun engine(env: TestEnv) = EscalationEngine(env.db, env.clock, env.notifier, env.rules, CircleService(env.db, env.clock, CircleEvents.NONE)).also {
        it.ordering = NearestOrdering(env.rules)
    }

    private fun current(env: TestEnv, incidentId: String): String? = env.db.connection.use { c ->
        c.createStatement().executeQuery("SELECT data->'order'->>((data->>'index')::int) FROM escalation_state WHERE incident_id = '$incidentId'")
            .use { it.next(); it.getString(1) }
    }

    @Test
    fun locationRejectedWithoutOpenIncident() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.family(env)
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val id = client.get("/v1/circles/$circleId/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
            client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FAMILY))
            client.post("/v1/incidents/$id/close/confirm") { bearerAuth("test.dad") }
            assertEquals(HttpStatusCode.Conflict, client.postJson("/v1/incidents/$id/my-location", "test.dad", MyLocationRequest(near(10.0), 8.0)).status)
        }
    }

    @Test
    fun locationsDeletedOnClose() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.family(env)
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val id = client.get("/v1/circles/$circleId/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
            assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/incidents/$id/my-location", "test.dad", MyLocationRequest(near(10.0), 8.0)).status)
            client.postJson("/v1/incidents/$id/close", "test.mom", CloseRequest(CloseReason.FAMILY))
            client.post("/v1/incidents/$id/close/confirm") { bearerAuth("test.dad") }
            env.db.connection.use { c -> c.createStatement().executeQuery("SELECT count(*) FROM member_locations").use { it.next(); assertEquals(0, it.getInt(1)) } }
        }
    }

    @Test
    fun nearestGuardianBecomesNextOwner() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.family(env)
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val id = client.get("/v1/circles/$circleId/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
            client.postJson("/v1/incidents/$id/my-location", "test.dad", MyLocationRequest(near(50.0), 8.0))
            client.postJson("/v1/incidents/$id/my-location", "test.uncle", MyLocationRequest(near(2_000.0), 8.0))
            val e = engine(env)
            env.clock.advance(20.seconds); e.tick()
            env.clock.advance(41.seconds); e.tick()
            val dad = client.get("/v1/circles/$circleId/members") { bearerAuth("test.mom") }.body<List<MemberDto>>().single { it.priority == 3 }
            assertEquals(dad.memberId, current(env, id), "dad is ≥300 m closer than the uncle, so he is next despite lower priority")
        }
    }

    @Test
    fun currentOwnerStableUnderJitter() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val (circleId, _, paired) = client.family(env)
            client.postJson("/v1/ingest", paired.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val id = client.get("/v1/circles/$circleId/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single().id
            val e = engine(env)
            val first = current(env, id)
            repeat(10) { i ->
                client.postJson("/v1/incidents/$id/my-location", "test.uncle", MyLocationRequest(near(if (i % 2 == 0) 20.0 else 50.0), 8.0))
                client.postJson("/v1/incidents/$id/my-location", "test.dad", MyLocationRequest(near(if (i % 2 == 0) 50.0 else 20.0), 8.0))
                env.clock.advance(2.seconds); e.tick()
                assertEquals(first, current(env, id))
            }
        }
    }
}
