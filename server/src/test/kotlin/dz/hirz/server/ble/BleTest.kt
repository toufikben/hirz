package dz.hirz.server.ble

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.PositionDto
import dz.hirz.shared.api.RelayBatch
import dz.hirz.shared.api.RelayFix
import dz.hirz.shared.api.RelayResult
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.api.Sighting
import dz.hirz.shared.api.TagRequest
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.signal.FixSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val BARN = LatLon(35.2, 4.5)
private fun north(m: Double) = LatLon(BARN.lat + m / 111_320.0, BARN.lon)
private const val TAG = "eddystone:0102030405060708090a0b0c0d0e0f"

private class Setup(val ownerCircle: String, val ewe: String, val relayToken: String, val relaySourceId: String)

/** mom's ewe wearing [TAG]; the shepherd (another circle) carries a paired Hirz phone that relays. */
private suspend fun HttpClient.setup(env: TestEnv, register: Boolean = true): Setup {
    val circle = newCircle("test.mom")
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Zriba", PlaceKind.BARN, ShapeDto.Circle(BARN, 40.0), marginM = 50))
    val ewe = newAsset("test.mom", circle.id, AssetType.LIVESTOCK, "Naaja")
    if (register) postJson("/v1/assets/${ewe.id}/tags", "test.mom", TagRequest(TAG, "eddystone", proofRssi = -48, proofAtMs = env.clock.millis()))
    val shepherdCircle = newCircle("test.shepherd")
    val phone = newAsset("test.shepherd", shepherdCircle.id, AssetType.LONE_WORKER, "Raai")
    val p: PairResponse = pair(pairingCode("test.shepherd", phone.id).code, adult = true, consent = true).body()
    return Setup(circle.id, ewe.id, p.sourceToken, p.sourceId)
}

private suspend fun HttpClient.relay(env: TestEnv, s: Setup, where: LatLon, rssi: Int, tag: String = TAG): RelayResult =
    postJson("/v1/relay/sightings", s.relayToken, RelayBatch(RelayFix(where, 10.0, env.clock.millis()), listOf(Sighting(tag, rssi, env.clock.millis())))).body()

class BleTest {
    @Test
    fun sightingBecomesAFixForTheTagsAsset() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.setup(env)
            assertEquals(1, client.relay(env, s, north(100.0), rssi = -55).accepted)
            val p = client.get("/v1/assets/${s.ewe}/positions?sinceMs=0") { bearerAuth("test.mom") }.body<List<PositionDto>>().single()
            assertEquals(FixSource.BLE_RELAY, p.fixSource)
            assertTrue((p.accuracyM ?: 0.0) >= 30.0, "the relay's accuracy plus the radio range")
        }
    }

    @Test
    fun sightingRevealsNothingAboutTheRelay() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.setup(env)
            client.relay(env, s, north(100.0), rssi = -55)
            val text = client.get("/v1/assets/${s.ewe}/positions?sinceMs=0") { bearerAuth("test.mom") }.bodyAsText() +
                client.get("/v1/circles/${s.ownerCircle}/assets") { bearerAuth("test.mom") }.bodyAsText()
            assertTrue(s.relaySourceId !in text && "Raai" !in text && "test.shepherd" !in text, "the relay stays anonymous: $text")
        }
    }

    @Test
    fun unknownTagIsDropped() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.setup(env, register = false)
            val r = client.relay(env, s, north(100.0), rssi = -55)
            assertEquals(0, r.accepted)
            assertEquals(1, r.dropped)
        }
    }

    @Test
    fun weakSightingNeverConfirmsABoundary() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.setup(env)
            env.clock.now = java.time.Instant.parse("2026-11-02T01:00:00Z")
            repeat(2) { client.relay(env, s, BARN, rssi = -50); env.clock.advance(31.seconds) }
            repeat(3) { client.relay(env, s, north(300.0), rssi = -95); env.clock.advance(31.seconds) }
            val open = client.get("/v1/circles/${s.ownerCircle}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>()
            assertTrue(open.isEmpty(), "a faint signal 300 m away says too little: $open")
        }
    }

    @Test
    fun tagNeedsProofOfPossession() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val ewe = client.newAsset("test.mom", circle.id, AssetType.LIVESTOCK, "Naaja")
            assertEquals(HttpStatusCode.UnprocessableEntity,
                client.postJson("/v1/assets/${ewe.id}/tags", "test.mom", TagRequest(TAG, "eddystone", proofRssi = -85, proofAtMs = env.clock.millis())).status,
                "a tag across the yard is not in hand")
            assertEquals(HttpStatusCode.UnprocessableEntity,
                client.postJson("/v1/assets/${ewe.id}/tags", "test.mom", TagRequest(TAG, "eddystone", proofRssi = -45, proofAtMs = env.clock.millis() - 600_000)).status,
                "a scan from ten minutes ago")
            assertEquals(HttpStatusCode.OK,
                client.postJson("/v1/assets/${ewe.id}/tags", "test.mom", TagRequest(TAG, "eddystone", proofRssi = -45, proofAtMs = env.clock.millis())).status)
            val other = client.newAsset("test.mom", circle.id, AssetType.LIVESTOCK, "Kebsh")
            assertEquals(HttpStatusCode.Conflict,
                client.postJson("/v1/assets/${other.id}/tags", "test.mom", TagRequest(TAG, "eddystone", proofRssi = -45, proofAtMs = env.clock.millis())).status,
                "one tag, one animal")
        }
    }

    @Test
    fun sightingsAreRateLimited() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.setup(env)
            assertEquals(1, client.relay(env, s, north(100.0), rssi = -55).accepted)
            env.clock.advance(10.seconds)
            val second = client.relay(env, s, north(100.0), rssi = -55)
            assertEquals(0, second.accepted)
            assertEquals(1, second.dropped)
        }
    }
}
