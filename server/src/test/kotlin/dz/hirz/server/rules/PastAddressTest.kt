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
private fun at(northM: Double, eastM: Double = 0.0) = LatLon(HOME.lat + northM / 111_320.0, HOME.lon + eastM / (111_320.0 * 0.8016))

private class Walker(val circleId: String, val token: String)

/** An elder of [condition] with a home and, when [address] is given, a past address there. */
private suspend fun HttpClient.walker(condition: Condition, address: LatLon?): Walker {
    val circle = newCircle("test.mom")
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Home", PlaceKind.HOME, ShapeDto.Circle(HOME, 50.0), marginM = 50))
    if (address != null) postJson("/v1/circles/${circle.id}/places", "test.mom",
        CreatePlaceRequest("Old house", PlaceKind.PAST_ADDRESS, ShapeDto.Circle(address, 30.0), marginM = 50))
    val asset = newAsset("test.mom", circle.id, AssetType.ELDER, "Jedda")
    put("/v1/assets/${asset.id}/profile") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(ProfileUpdate(condition = condition)) }
    val p: PairResponse = pair(pairingCode("test.mom", asset.id).code, adult = true, consent = true).body()
    return Walker(circle.id, p.sourceToken)
}

private suspend fun HttpClient.walk(env: TestEnv, w: Walker, vararg north: Double) = north.forEach { m ->
    postJson("/v1/ingest", w.token, IngestBatch(listOf(IngestSignal("p${env.clock.millis()}", env.clock.millis(), at(m), 10.0))))
    env.clock.advance(60.seconds)
}

private suspend fun HttpClient.open(w: Walker): List<IncidentDto> = get("/v1/circles/${w.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

class PastAddressTest {
    @Test
    fun walkingTowardChildhoodHomeRaises() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val w = client.walker(Condition.DEMENTIA, at(2000.0))
            client.walk(env, w, 0.0, 0.0, 150.0, 300.0, 450.0, 600.0)
            assertTrue(client.open(w).none { it.rule == RuleId.PAST_ADDRESS_HEADING }, "three steps closer are not yet a heading")
            client.walk(env, w, 750.0)
            assertEquals(Tier.P1, client.open(w).single { it.rule == RuleId.PAST_ADDRESS_HEADING }.tier)
        }
    }

    @Test
    fun passingByOnTheWayElsewhereDoesNot() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val w = client.walker(Condition.DEMENTIA, at(450.0, 150.0))
            client.walk(env, w, 0.0, 0.0, 150.0, 300.0, 450.0, 600.0, 750.0, 900.0)
            assertTrue(client.open(w).none { it.rule == RuleId.PAST_ADDRESS_HEADING })
        }
    }

    @Test
    fun noPastAddressNoRule() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val none = client.walker(Condition.DEMENTIA, null)
            client.walk(env, none, 0.0, 0.0, 150.0, 300.0, 450.0, 600.0, 750.0)
            assertTrue(client.open(none).none { it.rule == RuleId.PAST_ADDRESS_HEADING })
            val noDementia = client.walker(Condition.NONE, at(2000.0))
            client.walk(env, noDementia, 0.0, 0.0, 150.0, 300.0, 450.0, 600.0, 750.0)
            assertTrue(client.open(noDementia).none { it.rule == RuleId.PAST_ADDRESS_HEADING }, "the rule belongs to the dementia profile")
        }
    }
}
