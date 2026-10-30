package dz.hirz.server.vehicles

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.activateTracker
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.validImei
import dz.hirz.server.support.withApp
import dz.hirz.server.traccar.CommandResult
import dz.hirz.shared.api.EngineRequest
import dz.hirz.shared.api.EngineResultDto
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.TrackerClaimDto
import dz.hirz.shared.api.TrackerClaimRequest
import dz.hirz.shared.asset.AssetType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val IMEI = validImei("35933907512345")

private class Car(val circleId: String, val assetId: String)

/** mom's car with an activated GT06N (engine_cut capable), dad as guardian; optionally a theft incident open. */
private suspend fun HttpClient.car(env: TestEnv, theft: Boolean = true, model: String = "gt06n"): Car {
    val circle = newCircle("test.mom")
    join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
    val car = newAsset("test.mom", circle.id, AssetType.CAR, "Clio")
    val claim: TrackerClaimDto = postJson("/v1/assets/${car.id}/trackers", "test.mom", TrackerClaimRequest(model, IMEI, apn = "internet")).body()
    val last = activateTracker(IMEI, claim, env.clock.instant().plusSeconds(5), deviceId = 101)
    env.clock.now = last.plusSeconds(10)
    if (theft) env.db.connection.use { c ->
        c.createStatement().executeUpdate(
            "INSERT INTO incidents (asset_id, circle_id, rule, tier, opened_at) VALUES ('${car.id}', '${circle.id}', 'ARMED_MOVEMENT', 'P1', now())",
        )
    }
    return Car(circle.id, car.id)
}

private suspend fun HttpClient.engine(car: Car, action: String, who: String = "test.mom"): EngineResultDto =
    postJson("/v1/assets/${car.assetId}/engine", who, EngineRequest(action)).body()

class RemoteEngineTest {
    @Test
    fun guardianCannotStopEngine() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env)
            assertEquals(EngineResultDto("refused", "not_owner"), client.engine(car, "stop", "test.dad"))
            assertTrue(env.traccar.commands.isEmpty())
        }
    }

    @Test
    fun noIncidentNoStop() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env, theft = false)
            assertEquals("no_theft_incident", client.engine(car, "stop").reason)
        }
    }

    @Test
    fun unsupportedModelRefused() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env, model = "sinotrack-st901")
            assertEquals("not_supported", client.engine(car, "stop").reason)
        }
    }

    @Test
    fun offlineDeviceRefused() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env)
            env.traccar.status = "offline"
            assertEquals("device_offline", client.engine(car, "stop").reason)
            assertTrue(env.traccar.commands.isEmpty())
        }
    }

    @Test
    fun staleFixRefused() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env)
            env.clock.now = env.clock.now.plusSeconds(61)
            assertEquals("stale_fix", client.engine(car, "stop").reason)
        }
    }

    @Test
    fun fastVehicleRefused() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env)
            env.db.connection.use { c -> c.createStatement().executeUpdate("UPDATE positions SET speed_kmh = 25") }
            assertEquals("too_fast", client.engine(car, "stop").reason)
        }
    }

    @Test
    fun queuedEngineStopIsWithdrawn() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env)
            env.traccar.commandResult = CommandResult.QUEUED
            assertEquals(EngineResultDto("refused", "queued_withdrawn"), client.engine(car, "stop"))
            assertEquals(listOf(101L), env.traccar.queuedDeleted.toList(), "never left queued to run later at speed")
        }
    }

    @Test
    fun stopSentAndAuditedOnIncident() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env)
            assertEquals(EngineResultDto("sent", null), client.engine(car, "stop"))
            assertEquals(listOf(101L to "engineStop"), env.traccar.commands.toList())
            val incident = client.get("/v1/circles/${car.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().single()
            val detail: IncidentDto = client.get("/v1/incidents/${incident.id}") { bearerAuth("test.mom") }.body()
            assertTrue(detail.events.any { it.kind == "engine_stop" })
            env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM engine_commands WHERE outcome = 'sent'").use { it.next(); assertEquals(1, it.getInt(1)) }
            }
        }
    }

    @Test
    fun resumeAlwaysAllowedForOwner() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env, theft = false)
            env.traccar.status = "offline"
            env.traccar.commandResult = CommandResult.QUEUED
            assertEquals("sent", client.engine(car, "resume").outcome, "a queued resume is safe")
            assertEquals(listOf(101L to "engineResume"), env.traccar.commands.toList())
            assertEquals("not_owner", client.engine(car, "resume", "test.dad").reason)
        }
    }

    @Test
    fun unknownSpeedOrFutureFixRefused() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env)
            env.db.connection.use { c -> c.createStatement().executeUpdate("UPDATE positions SET speed_kmh = NULL") }
            assertEquals("no_speed", client.engine(car, "stop").reason)
            env.db.connection.use { c -> c.createStatement().executeUpdate("UPDATE positions SET speed_kmh = 0, fix_time = fix_time + interval '3 minutes'") }
            assertEquals("stale_fix", client.engine(car, "stop").reason, "a fix from the future is not fresh")
            assertTrue(env.traccar.commands.isEmpty())
        }
    }

    @Test
    fun gatewayThatCannotRefuseQueueingIsNotTrusted() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env)
            env.traccar.version = "6.6.0"
            assertEquals("gateway_unsupported", client.engine(car, "stop").reason)
            assertTrue(env.traccar.commands.isEmpty())
        }
    }

    @Test
    fun failedWithdrawalIsSaidPlainly() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val car = client.car(env)
            env.traccar.commandResult = CommandResult.QUEUED
            env.traccar.withdrawFails = true
            assertEquals(EngineResultDto("unknown", "withdraw_failed"), client.engine(car, "stop"))
            env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT outcome, reason FROM engine_commands").use {
                    it.next(); assertEquals("unknown", it.getString(1)); assertEquals("withdraw_failed", it.getString(2))
                }
            }
        }
    }
}
