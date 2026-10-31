package dz.hirz.server.vehicles

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.RelayBatch
import dz.hirz.shared.api.RelayFix
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Sighting
import dz.hirz.shared.api.TagRequest
import dz.hirz.shared.api.Tier
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.api.VehicleStateDto
import dz.hirz.shared.asset.AssetType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val PARKED = LatLon(36.7538, 3.0588)
private const val CAR_TAG = "ibeacon:fda50693-a4e2-4fb1-afcf-c6eb07647825:7:7"

private class TaggedCar(val circleId: String, val carId: String, val carToken: String, val phoneToken: String)

/** mom's car on a phone-as-tracker, optionally with a BLE tag, and mom's own Hirz phone in the same circle (the relay). */
private suspend fun HttpClient.car(env: TestEnv, tag: Boolean = true): TaggedCar {
    val circle = newCircle("test.mom")
    val car = newAsset("test.mom", circle.id, AssetType.CAR, "Clio")
    if (tag) postJson("/v1/assets/${car.id}/tags", "test.mom", TagRequest(CAR_TAG, "ibeacon", proofRssi = -45, proofAtMs = env.clock.millis()))
    val carSource: PairResponse = pair(pairingCode("test.mom", car.id).code).body()
    val phoneAsset = newAsset("test.mom", circle.id, AssetType.LONE_WORKER, "Mom")
    val phone: PairResponse = pair(pairingCode("test.mom", phoneAsset.id).code, adult = true, consent = true).body()
    return TaggedCar(circle.id, car.id, carSource.sourceToken, phone.sourceToken)
}

private suspend fun HttpClient.ignition(env: TestEnv, c: TaggedCar, on: Boolean) =
    postJson("/v1/ingest", c.carToken, IngestBatch(listOf(IngestSignal("i${env.clock.millis()}", env.clock.millis(), PARKED, 8.0, ignition = on))))

private suspend fun HttpClient.seen(env: TestEnv, c: TaggedCar) =
    postJson("/v1/relay/sightings", c.phoneToken, RelayBatch(RelayFix(PARKED, 10.0, env.clock.millis()), listOf(Sighting(CAR_TAG, -60, env.clock.millis()))))

private suspend fun HttpClient.state(c: TaggedCar): VehicleStateDto = get("/v1/assets/${c.carId}/vehicle") { bearerAuth("test.mom") }.body()

private suspend fun HttpClient.open(c: TaggedCar): List<IncidentDto> = get("/v1/circles/${c.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

class PresenceArmTest {
    @Test
    fun armsWhenOwnerPhoneLeaves2min() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val c = client.car(env)
            client.ignition(env, c, false)
            client.seen(env, c)
            env.clock.advance(3.minutes)
            val s = client.state(c)
            assertEquals(VehicleMode.ARMED, s.mode, "mom walked away two minutes ago: no need to wait the 10-minute auto-arm")
            assertEquals("owner_away", s.reason)
        }
    }

    @Test
    fun driverPhoneNearbyDoesNotArm() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val c = client.car(env)
            client.ignition(env, c, false)
            repeat(6) { client.seen(env, c); env.clock.advance(31.seconds) }
            assertEquals(VehicleMode.DISARMED, client.state(c).mode, "mom is still by the car")
        }
    }

    @Test
    fun ignitionWithNoKnownPhoneIsKeyless() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val c = client.car(env)
            client.ignition(env, c, false)
            env.clock.advance(1.minutes)
            client.ignition(env, c, true)
            assertEquals(Tier.P1, client.open(c).single { it.rule == RuleId.KEYLESS_NO_OWNER }.tier, "started with no family phone near: a relay attack")
        }
    }

    @Test
    fun noTagNoKeylessRule() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val c = client.car(env, tag = false)
            client.ignition(env, c, false)
            env.clock.advance(1.minutes)
            client.ignition(env, c, true)
            assertTrue(client.open(c).none { it.rule == RuleId.KEYLESS_NO_OWNER }, "without a tag the car cannot know who is near")
        }
    }
}
