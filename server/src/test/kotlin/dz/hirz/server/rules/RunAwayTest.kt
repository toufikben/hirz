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
import dz.hirz.shared.api.ProfileUpdate
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.asset.Condition
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val HOME = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(HOME.lat + m / 111_320.0, HOME.lon)

private class Child(val circleId: String, val token: String)

/** A home (20 m) and a paired child of [condition]; no fix sent yet. */
private suspend fun HttpClient.child(condition: Condition): Child {
    val circle = newCircle("test.mom")
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Home", PlaceKind.HOME, ShapeDto.Circle(HOME, 20.0), marginM = 50))
    val asset = newAsset("test.mom", circle.id, AssetType.CHILD, "Yanis")
    put("/v1/assets/${asset.id}/profile") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(ProfileUpdate(condition = condition)) }
    val p: PairResponse = pair(pairingCode("test.mom", asset.id).code).body()
    return Child(circle.id, p.sourceToken)
}

/** One fix [m] metres north at [kmh], 10 s after the last. */
private suspend fun HttpClient.at(env: TestEnv, c: Child, m: Double, kmh: Double, acc: Double = 8.0) {
    postJson("/v1/ingest", c.token, IngestBatch(listOf(IngestSignal("r${env.clock.millis()}", env.clock.millis(), north(m), acc, speedKmh = kmh))))
    env.clock.advance(10.seconds)
}

private suspend fun HttpClient.open(c: Child): List<IncidentDto> = get("/v1/circles/${c.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

class RunAwayTest {
    @Test
    fun boltingFromHomeIsP0() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val c = client.child(Condition.AUTISM)
            client.at(env, c, 0.0, 0.0); client.at(env, c, 0.0, 0.0)
            client.at(env, c, 40.0, 11.0)
            assertTrue(client.open(c).none { it.rule == RuleId.RUN_AWAY_SPEED })
            client.at(env, c, 75.0, 12.0)
            assertEquals(Tier.P0, client.open(c).single { it.rule == RuleId.RUN_AWAY_SPEED }.tier)
        }
    }

    @Test
    fun cyclingTowardHomeIsNotRunningAway() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val c = client.child(Condition.AUTISM)
            listOf(500.0, 450.0, 400.0, 350.0).forEach { client.at(env, c, it, 18.0) }
            assertTrue(client.open(c).none { it.rule == RuleId.RUN_AWAY_SPEED })
        }
    }

    @Test
    fun oneFastFixIsNotEnough() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val c = client.child(Condition.AUTISM)
            client.at(env, c, 0.0, 0.0); client.at(env, c, 0.0, 0.0)
            client.at(env, c, 40.0, 11.0)
            client.at(env, c, 45.0, 3.0)
            client.at(env, c, 50.0, 3.0)
            assertTrue(client.open(c).none { it.rule == RuleId.RUN_AWAY_SPEED })
        }
    }

    @Test
    fun coarseFixesNeitherCountNorReset() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val c = client.child(Condition.AUTISM)
            client.at(env, c, 0.0, 0.0); client.at(env, c, 0.0, 0.0)
            client.at(env, c, 40.0, 11.0)
            client.at(env, c, 300.0, 3.0, acc = 60.0)
            assertTrue(client.open(c).none { it.rule == RuleId.RUN_AWAY_SPEED }, "a coarse fix does not count")
            client.at(env, c, 80.0, 12.0)
            assertEquals(1, client.open(c).count { it.rule == RuleId.RUN_AWAY_SPEED }, "nor did it reset the streak")
        }
    }

    @Test
    fun ruleOffForNonAutism() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val c = client.child(Condition.NONE)
            client.at(env, c, 0.0, 0.0); client.at(env, c, 0.0, 0.0)
            client.at(env, c, 40.0, 11.0); client.at(env, c, 75.0, 12.0)
            assertTrue(client.open(c).none { it.rule == RuleId.RUN_AWAY_SPEED })
        }
    }

    @Test
    fun boltingWithFixesEvery5sAndNoSpeed() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val c = client.child(Condition.AUTISM)
            listOf(0.0, 0.0, 14.0, 28.0, 42.0).forEach { m ->
                client.postJson("/v1/ingest", c.token, IngestBatch(listOf(IngestSignal("q${env.clock.millis()}", env.clock.millis(), north(m), 8.0))))
                env.clock.advance(5.seconds)
            }
            assertEquals(Tier.P0, client.open(c).single { it.rule == RuleId.RUN_AWAY_SPEED }.tier, "10 km/h every 5 s, speed derived from the fixes")
        }
    }
}
