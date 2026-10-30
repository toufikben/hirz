package dz.hirz.server.rules

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.AuthorizationRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.ModeRequest
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.asset.AssetType
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

private val PARKED = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(PARKED.lat + m / 111_320.0, PARKED.lon)

private class Car(val circleId: String, val assetId: String, val token: String)

private suspend fun HttpClient.car(env: TestEnv): Car {
    val circle = newCircle("test.mom")
    put("/v1/me/push-token") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(PushTokenDto("tok-mom")) }
    val car = newAsset("test.mom", circle.id, AssetType.CAR, "Clio")
    val p: PairResponse = pair(pairingCode("test.mom", car.id).code).body()
    postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(IngestSignal("park", env.clock.millis(), PARKED, 8.0, ignition = false))))
    return Car(circle.id, car.id, p.sourceToken)
}

private suspend fun HttpClient.drive(env: TestEnv, car: Car, tag: String, metres: Double, speed: Double) {
    env.clock.advance(30.seconds)
    postJson("/v1/ingest", car.token, IngestBatch(listOf(IngestSignal(tag, env.clock.millis(), north(metres), 8.0, speedKmh = speed, ignition = true))))
}

private suspend fun HttpClient.open(car: Car): List<IncidentDto> = get("/v1/circles/${car.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

class VehicleRulesHttpTest {
    @Test
    fun armedCarMovedOpensIncident() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env)
            client.postJson("/v1/assets/${car.assetId}/mode", "test.mom", ModeRequest(VehicleMode.ARMED))
            client.drive(env, car, "d1", 0.0, 0.0)
            assertEquals(listOf(RuleId.ARMED_MOVEMENT to Tier.P1), client.open(car).map { it.rule to it.tier })
            assertTrue(env.notifier.alerts.isNotEmpty())
        }
    }

    @Test
    fun authorizedDriveRaisesNothing() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env)
            client.postJson("/v1/assets/${car.assetId}/mode", "test.mom", ModeRequest(VehicleMode.ARMED))
            client.postJson("/v1/assets/${car.assetId}/authorizations", "test.mom", AuthorizationRequest(hours = 2))
            listOf(0.0, 300.0, 900.0, 2_000.0).forEachIndexed { i, m -> client.drive(env, car, "a$i", m, 45.0) }
            assertTrue(client.open(car).isEmpty())
        }
    }

    @Test
    fun p0FindingRaisesAnOpenP1() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env)
            client.postJson("/v1/assets/${car.assetId}/mode", "test.mom", ModeRequest(VehicleMode.ARMED))
            client.drive(env, car, "d1", 0.0, 0.0)
            assertEquals(Tier.P1, client.open(car).single().tier)
            val before = env.notifier.alerts.size
            client.drive(env, car, "d2", 200.0, 30.0)
            val raised = client.open(car).single()
            assertEquals(Tier.P0, raised.tier, "one incident, raised to P0")
            assertTrue(raised.events.any { it.kind == "tier_raised" })
            assertTrue(env.notifier.alerts.size > before, "everyone is alerted again at P0")
            val ladder = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT data->>'tier' FROM escalation_state WHERE incident_id = '${raised.id}'").use { it.next(); it.getString(1) }
            }
            assertEquals("P0", ladder, "the ladder restarts at P0 windows")
        }
    }
}
