package dz.hirz.server.learning

import dz.hirz.server.jobs.LearningJob
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val SCHOOL = LatLon(36.7538, 3.0588)
private val GRANDMA = LatLon(36.7638, 3.0688) // about 1.4 km away
private fun north(m: Double) = LatLon(SCHOOL.lat + m / 111_320.0, SCHOOL.lon)

private class Learner(val circleId: String, val assetId: String, val token: String)

private suspend fun HttpClient.learner(): Learner {
    val circle = newCircle("test.mom")
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Ecole", PlaceKind.SCHOOL, ShapeDto.Circle(SCHOOL, 100.0), marginM = 50))
    val kid = newAsset("test.mom", circle.id)
    val p: PairResponse = pair(pairingCode("test.mom", kid.id).code).body()
    return Learner(circle.id, kid.id, p.sourceToken)
}

/** A 30-minute visit at [where], [daysAgo] days before the clock: four fixes ten minutes apart. */
private suspend fun HttpClient.visit(env: TestEnv, l: Learner, where: LatLon, daysAgo: Long) {
    val start = env.clock.millis() - daysAgo * 86_400_000L
    postJson("/v1/ingest", l.token, IngestBatch((0 until 4).map { i ->
        IngestSignal("v$daysAgo-$i-${where.lat}", start + i * 600_000L, LatLon(where.lat + i * 0.00002, where.lon), 12.0)
    }))
}

private fun learned(env: TestEnv): Int = env.db.connection.use { c ->
    c.createStatement().executeQuery("SELECT count(*) FROM learned_places").use { it.next(); it.getInt(1) }
}

private suspend fun HttpClient.open(l: Learner): List<IncidentDto> = get("/v1/circles/${l.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

class LearningTest {
    @Test
    fun learnsGrandmasHouse() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val l = client.learner()
            listOf(9L, 6L, 3L).forEach { client.visit(env, l, GRANDMA, it) }
            LearningJob(env.db, env.clock, env.rules).runOnce()
            assertEquals(1, learned(env), "three afternoons at grandma's: a place of hers")
        }
    }

    @Test
    fun oneVisitIsNotLearned() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val l = client.learner()
            client.visit(env, l, GRANDMA, 3)
            LearningJob(env.db, env.clock, env.rules).runOnce()
            assertEquals(0, learned(env))
        }
    }

    @Test
    fun newPlaceIsASupportingReason() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val l = client.learner()
            listOf(0.0, 0.0, 300.0, 310.0, 320.0).forEach { m ->
                client.postJson("/v1/ingest", l.token, IngestBatch(listOf(IngestSignal("x${env.clock.millis()}", env.clock.millis(), north(m), 10.0))))
                env.clock.advance(10.seconds)
            }
            val exit = client.open(l).single { it.rule == RuleId.PLACE_EXIT }
            assertTrue(exit.reasons.any { it.code == "new_place" }, "left school toward a place never visited: ${exit.reasons}")
        }
    }

    @Test
    fun learningNeverRaises() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val l = client.learner()
            (1..10).forEach { i -> client.visit(env, l, LatLon(GRANDMA.lat + i * 0.01, GRANDMA.lon), 1) }
            LearningJob(env.db, env.clock, env.rules).runOnce()
            assertTrue(client.open(l).isEmpty(), "ten new places in a day open nothing on their own")
        }
    }

    @Test
    fun learnedPlacesNeedRecentVisits() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val l = client.learner()
            listOf(50L, 45L, 40L).forEach { client.visit(env, l, GRANDMA, it) }
            LearningJob(env.db, env.clock, env.rules).runOnce()
            assertEquals(0, learned(env), "visits older than the learning window teach nothing")
        }
    }
}
