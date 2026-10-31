package dz.hirz.server.rules

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
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.AssetType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val HOME = LatLon(36.7538, 3.0588)
private fun at(north: Double, east: Double) = LatLon(HOME.lat + north / 111_320.0, HOME.lon + east / (111_320.0 * 0.8016))

private class Pacer(val circleId: String, val token: String)

/** An elder with a home of [homeRadius] m; adult consent. */
private suspend fun HttpClient.elder(homeRadius: Double = 50.0): Pacer {
    val circle = newCircle("test.mom")
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Dar", PlaceKind.HOME, ShapeDto.Circle(HOME, homeRadius)))
    val a = newAsset("test.mom", circle.id, AssetType.ELDER, "Jeddi")
    val p: PairResponse = pair(pairingCode("test.mom", a.id).code, adult = true, consent = true).body()
    return Pacer(circle.id, p.sourceToken)
}

/** The corners of a 200 m square around a block 500 m north of home, walked [laps] times, one fix every 30 s (≈40 m). */
private fun block(laps: Int): List<LatLon> {
    val corners = listOf(0.0 to 0.0, 0.0 to 200.0, 200.0 to 200.0, 200.0 to 0.0)
    val one = corners.indices.flatMap { i ->
        val (n1, e1) = corners[i]; val (n2, e2) = corners[(i + 1) % 4]
        (0 until 5).map { s -> at(500 + n1 + (n2 - n1) * s / 5, e1 + (e2 - e1) * s / 5) }
    }
    return (1..laps).flatMap { one }
}

private suspend fun HttpClient.walk(env: TestEnv, w: Pacer, points: List<LatLon>) = points.forEachIndexed { i, p ->
    postJson("/v1/ingest", w.token, IngestBatch(listOf(IngestSignal("w$i", env.clock.millis(), p, 10.0))))
    env.clock.advance(30.seconds)
}

private suspend fun HttpClient.open(w: Pacer): List<IncidentDto> = get("/v1/circles/${w.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

class PacingTest {
    @Test
    fun circlingTheSameBlockRaisesP2() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val w = client.elder()
            client.walk(env, w, block(3))
            assertEquals(Tier.P2, client.open(w).single { it.rule == RuleId.PACING_LOOP }.tier)
        }
    }

    @Test
    fun aLongStraightWalkIsNotPacing() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val w = client.elder()
            client.walk(env, w, (0 until 60).map { at(500.0 + it * 40.0, 0.0) })
            assertTrue(client.open(w).none { it.rule == RuleId.PACING_LOOP })
        }
    }

    @Test
    fun walkingInTheGardenAtHomeIsNot() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val w = client.elder(homeRadius = 900.0)
            client.walk(env, w, block(3))
            assertTrue(client.open(w).none { it.rule == RuleId.PACING_LOOP }, "inside home, laps are a walk in the garden")
        }
    }
}
