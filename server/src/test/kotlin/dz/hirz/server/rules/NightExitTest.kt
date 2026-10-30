package dz.hirz.server.rules

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.NightException
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.ProfileUpdate
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.AssetType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val HOME = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(HOME.lat + m / 111_320.0, HOME.lon)

private class Elder(val circleId: String, val assetId: String, val token: String, val mosqueId: String)

/** An elder with a home (50 m) and a mosque 400 m north; the clock at [at]; two fixes at home. */
private suspend fun HttpClient.elder(env: TestEnv, at: Instant): Elder {
    env.clock.now = at
    val circle = newCircle("test.mom")
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Home", PlaceKind.HOME, ShapeDto.Circle(HOME, 50.0), marginM = 50))
    val mosque: PlaceDto = postJson("/v1/circles/${circle.id}/places", "test.mom",
        CreatePlaceRequest("Mosque", PlaceKind.MOSQUE, ShapeDto.Circle(north(400.0), 30.0), marginM = 50)).body()
    val asset = newAsset("test.mom", circle.id, AssetType.ELDER, "Jeddi")
    val p: PairResponse = pair(pairingCode("test.mom", asset.id).code, adult = true, consent = true).body()
    return Elder(circle.id, asset.id, p.sourceToken, mosque.id).also { walk(env, it, 0.0, 0.0) }
}

private suspend fun HttpClient.walk(env: TestEnv, e: Elder, vararg metres: Double, acc: Double = 10.0) {
    metres.forEach { m ->
        postJson("/v1/ingest", e.token, IngestBatch(listOf(IngestSignal("w${env.clock.millis()}", env.clock.millis(), north(m), acc))))
        env.clock.advance(10.seconds)
    }
}

private suspend fun HttpClient.open(e: Elder): List<IncidentDto> = get("/v1/circles/${e.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

class NightExitTest {
    @Test
    fun leavingHomeAt2amRaisesNightExit() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val e = client.elder(env, Instant.parse("2026-11-02T01:00:00Z")) // 02:00 Algiers
            client.walk(env, e, 200.0)
            assertTrue(client.open(e).isEmpty())
            client.walk(env, e, 210.0)
            assertEquals(listOf(RuleId.NIGHT_EXIT to Tier.P1), client.open(e).map { it.rule to it.tier }, "two readings at night, not three")
        }
    }

    @Test
    fun leavingHomeAt2pmIsPlaceExitAsBefore() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val e = client.elder(env, Instant.parse("2026-11-02T13:00:00Z")) // 14:00 Algiers
            client.walk(env, e, 200.0, 210.0)
            assertTrue(client.open(e).isEmpty())
            client.walk(env, e, 220.0)
            assertEquals(listOf(RuleId.PLACE_EXIT), client.open(e).map { it.rule })
        }
    }

    @Test
    fun fajrToTheMosqueIsNotANightExit() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val e = client.elder(env, Instant.parse("2026-11-02T03:45:00Z")) // 04:45 Algiers
            client.put("/v1/assets/${e.assetId}/profile") {
                bearerAuth("test.mom"); contentType(ContentType.Application.Json)
                setBody(ProfileUpdate(nightExceptions = listOf(NightException(e.mosqueId, ActiveWindow((1..7).toSet(), 4 * 60 + 30, 6 * 60 + 30)))))
            }
            client.walk(env, e, 150.0, 250.0, 350.0, 400.0, 405.0)
            assertTrue(client.open(e).isEmpty(), "the owner said Fajr at the mosque is expected")
        }
    }

    @Test
    fun wanderingPastTheMosqueStillRaises() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val e = client.elder(env, Instant.parse("2026-11-02T03:45:00Z"))
            client.put("/v1/assets/${e.assetId}/profile") {
                bearerAuth("test.mom"); contentType(ContentType.Application.Json)
                setBody(ProfileUpdate(nightExceptions = listOf(NightException(e.mosqueId, ActiveWindow((1..7).toSet(), 4 * 60 + 30, 6 * 60 + 30)))))
            }
            client.walk(env, e, 150.0, 250.0, 400.0, 700.0, 900.0)
            assertEquals(listOf(RuleId.NIGHT_EXIT), client.open(e).map { it.rule }, "far beyond the mosque is not the way to prayer")
        }
    }

    @Test
    fun driftAtTheDoorIsNotAnExit() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val e = client.elder(env, Instant.parse("2026-11-02T01:00:00Z"))
            client.walk(env, e, 120.0, 130.0, 140.0, acc = 90.0)
            assertTrue(client.open(e).isEmpty())
        }
    }
}
