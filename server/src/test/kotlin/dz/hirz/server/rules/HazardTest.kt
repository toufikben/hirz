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

private val POOL = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(POOL.lat + m / 111_320.0, POOL.lon)

private class Kid(val circleId: String, val token: String)

/** A circle with one place of [kind] (30 m around POOL) and a paired child of [condition]; the child starts 300 m away. */
private suspend fun HttpClient.kid(env: TestEnv, kind: PlaceKind, condition: Condition = Condition.NONE): Kid {
    val circle = newCircle("test.mom")
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Pool", kind, ShapeDto.Circle(POOL, 30.0), marginM = 50))
    val asset = newAsset("test.mom", circle.id, AssetType.CHILD, "Yanis")
    if (condition != Condition.NONE) put("/v1/assets/${asset.id}/profile") {
        bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(ProfileUpdate(condition = condition))
    }
    val p: PairResponse = pair(pairingCode("test.mom", asset.id).code).body()
    return Kid(circle.id, p.sourceToken).also { fix(env, it, 300.0, 8.0) }
}

private suspend fun HttpClient.fix(env: TestEnv, k: Kid, m: Double, acc: Double) {
    postJson("/v1/ingest", k.token, IngestBatch(listOf(IngestSignal("f${env.clock.millis()}", env.clock.millis(), north(m), acc))))
    env.clock.advance(10.seconds)
}

private suspend fun HttpClient.open(k: Kid): List<IncidentDto> = get("/v1/circles/${k.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

class HazardTest {
    @Test
    fun hazardEntryIsP0ForAutismAfterOneGoodFix() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val k = client.kid(env, PlaceKind.HAZARD, Condition.AUTISM)
            client.fix(env, k, 0.0, 8.0)
            val hit = client.open(k).single()
            assertEquals(RuleId.HAZARD_PROXIMITY, hit.rule)
            assertEquals(Tier.P0, hit.tier)
        }
    }

    @Test
    fun coarseFixAtTheWaterIsNotEntry() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val k = client.kid(env, PlaceKind.HAZARD, Condition.AUTISM)
            client.fix(env, k, 0.0, 40.0)
            client.fix(env, k, 5.0, 45.0)
            assertTrue(client.open(k).isEmpty(), "a ±40 m fix cannot say the child is in a 30 m pool")
        }
    }

    @Test
    fun hazardForAChildIsP1AfterTwoReadings() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val k = client.kid(env, PlaceKind.HAZARD)
            client.fix(env, k, 0.0, 8.0)
            assertTrue(client.open(k).isEmpty())
            client.fix(env, k, 2.0, 8.0)
            val hit = client.open(k).single()
            assertEquals(RuleId.HAZARD_PROXIMITY, hit.rule)
            assertEquals(Tier.P1, hit.tier)
        }
    }

    @Test
    fun forbiddenEntryUnchanged() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val k = client.kid(env, PlaceKind.FORBIDDEN, Condition.AUTISM)
            client.fix(env, k, 0.0, 8.0)
            client.fix(env, k, 2.0, 8.0)
            assertTrue(client.open(k).isEmpty(), "forbidden places keep three readings, whatever the profile")
            client.fix(env, k, 4.0, 8.0)
            assertEquals(listOf(RuleId.FORBIDDEN_ENTRY to Tier.P1), client.open(k).map { it.rule to it.tier })
        }
    }
}
