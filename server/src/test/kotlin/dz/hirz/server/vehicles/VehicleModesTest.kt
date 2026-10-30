package dz.hirz.server.vehicles

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.join
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.AuthorizationRequest
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.ModeRequest
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.api.VehicleStateDto
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.rules.RuleConfig
import dz.hirz.shared.signal.AlarmKind
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val T0 = Instant.parse("2026-11-01T08:00:00Z") // 09:00 Algiers, a Sunday
private val cfg = RuleConfig.DEFAULT

private fun input(
    override: ModeOverride? = null,
    authorizations: List<AuthorizationWindow> = emptyList(),
    schedule: List<ActiveWindow> = emptyList(),
    ignitionOn: Boolean? = null,
    ignitionSince: Instant? = null,
) = ModeInput(override, authorizations, schedule, ignitionOn, ignitionSince)

class VehicleModesTest {
    @Test
    fun authorizationBeatsSchedule() {
        val night = listOf(ActiveWindow((1..7).toSet(), 0, 1440))
        val r = ModeResolver.resolve(input(authorizations = listOf(AuthorizationWindow(T0.minusSeconds(60), T0.plusSeconds(3600))), schedule = night), T0, cfg)
        assertEquals(VehicleMode.AUTHORIZED, r.mode)
        assertEquals("authorized", r.reason)
    }

    @Test
    fun scheduleArmsAtNightOnlyWithIgnitionOff() {
        val allDay = listOf(ActiveWindow((1..7).toSet(), 0, 1440))
        assertEquals(VehicleMode.ARMED, ModeResolver.resolve(input(schedule = allDay, ignitionOn = false, ignitionSince = T0.minusSeconds(60)), T0, cfg).mode)
        assertEquals(VehicleMode.DISARMED, ModeResolver.resolve(input(schedule = allDay, ignitionOn = true, ignitionSince = T0.minusSeconds(60)), T0, cfg).mode,
            "a schedule never arms a car that is being driven")
    }

    @Test
    fun autoArmAfterIgnitionOff10Min() {
        val parked = input(ignitionOn = false, ignitionSince = T0)
        assertEquals(VehicleMode.DISARMED, ModeResolver.resolve(parked, T0.plus(Duration.ofMinutes(9)), cfg).mode)
        val r = ModeResolver.resolve(parked, T0.plus(Duration.ofMinutes(10)), cfg)
        assertEquals(VehicleMode.ARMED, r.mode)
        assertEquals("auto_arm", r.reason)
    }

    @Test
    fun manualDisarmLastsUntilTheNextParking() {
        val disarmed = ModeOverride(VehicleMode.DISARMED, T0)
        assertEquals(VehicleMode.DISARMED, ModeResolver.resolve(input(override = disarmed, ignitionOn = false, ignitionSince = T0.minusSeconds(3600)), T0.plusSeconds(1200), cfg).mode,
            "parked before the manual disarm: it stays disarmed")
        assertEquals(VehicleMode.ARMED, ModeResolver.resolve(input(override = disarmed, ignitionOn = false, ignitionSince = T0.plusSeconds(60)), T0.plusSeconds(60 + 600), cfg).mode,
            "a new parking after the disarm auto-arms")
        assertEquals(VehicleMode.MAINTENANCE, ModeResolver.resolve(input(override = ModeOverride(VehicleMode.MAINTENANCE, T0), ignitionOn = false, ignitionSince = T0.plusSeconds(60)), T0.plusSeconds(3600), cfg).mode,
            "maintenance holds until changed")
    }

    @Test
    fun authorizedWindowEndingMidDriveDoesNotAlarm() {
        val auth = listOf(AuthorizationWindow(T0.minusSeconds(7200), T0))
        val driving = ModeResolver.resolve(input(authorizations = auth, ignitionOn = true, ignitionSince = T0.minusSeconds(3600)), T0.plusSeconds(1), cfg)
        assertEquals(VehicleMode.DISARMED, driving.mode)
        assertEquals("expired", driving.reason)
        val parked = input(authorizations = auth, ignitionOn = false, ignitionSince = T0.plusSeconds(600))
        assertEquals(VehicleMode.DISARMED, ModeResolver.resolve(parked, T0.plusSeconds(600 + 599), cfg).mode)
        assertEquals(VehicleMode.ARMED, ModeResolver.resolve(parked, T0.plusSeconds(600 + 600), cfg).mode)
    }

    // ---- HTTP ----

    @Test
    fun armRecordsPositionAndSwitch() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val car = client.newAsset("test.mom", circle.id, AssetType.CAR, "Clio")
            val p: PairResponse = client.pair(client.pairingCode("test.mom", car.id).code).body()
            client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(IngestSignal("f", env.clock.millis(), LatLon(36.75, 3.05), 10.0))))
            val armed: VehicleStateDto = client.postJson("/v1/assets/${car.id}/mode", "test.mom", ModeRequest(VehicleMode.ARMED)).body()
            assertEquals(VehicleMode.ARMED, armed.mode)
            assertEquals(LatLon(36.75, 3.05), armed.armedPosition)
            val switches = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM mode_switches WHERE asset_id = '${car.id}' AND to_mode = 'armed' AND reason = 'manual'").use { it.next(); it.getInt(1) }
            }
            assertEquals(1, switches)
            val child = client.newAsset("test.mom", circle.id)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postJson("/v1/assets/${child.id}/mode", "test.mom", ModeRequest(VehicleMode.ARMED)).status)
        }
    }

    @Test
    fun viewerCannotArm() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.join("test.mom", circle.id, "test.aunt", Role.VIEWER)
            val car = client.newAsset("test.mom", circle.id, AssetType.CAR, "Clio")
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/assets/${car.id}/mode", "test.aunt", ModeRequest(VehicleMode.ARMED)).status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/assets/${car.id}/vehicle") { bearerAuth("test.aunt") }.status)
        }
    }

    @Test
    fun disarmDuringIncidentIsRefused() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val car = client.newAsset("test.mom", circle.id, AssetType.CAR, "Clio")
            val p: PairResponse = client.pair(client.pairingCode("test.mom", car.id).code).body()
            client.postJson("/v1/assets/${car.id}/mode", "test.mom", ModeRequest(VehicleMode.ARMED))
            client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            assertEquals(HttpStatusCode.Conflict, client.postJson("/v1/assets/${car.id}/mode", "test.mom", ModeRequest(VehicleMode.DISARMED)).status)
            assertEquals(HttpStatusCode.Conflict, client.postJson("/v1/assets/${car.id}/authorizations", "test.mom", AuthorizationRequest(hours = 2)).status)
            assertEquals(HttpStatusCode.OK, client.postJson("/v1/assets/${car.id}/mode", "test.mom", ModeRequest(VehicleMode.ARMED)).status, "arming is always allowed")
        }
    }

    @Test
    fun authorizationExpiresAndIsAudited() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            val car = client.newAsset("test.mom", circle.id, AssetType.CAR, "Clio")
            val s: VehicleStateDto = client.postJson("/v1/assets/${car.id}/authorizations", "test.mom", AuthorizationRequest(hours = 2, driverName = "Karim")).body()
            assertEquals(VehicleMode.AUTHORIZED, s.mode)
            assertEquals("Karim", assertNotNull(s.authorization).driverName)
            env.clock.advance(2.hours + 1.minutes)
            val after: VehicleStateDto = client.get("/v1/assets/${car.id}/vehicle") { bearerAuth("test.mom") }.body()
            assertEquals(VehicleMode.DISARMED, after.mode)
            assertEquals("expired", after.reason)
            env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM audit_log WHERE action = 'vehicle_authorized'").use { it.next(); assertEquals(1, it.getInt(1)) }
                c.createStatement().executeQuery("SELECT count(*) FROM mode_switches WHERE asset_id = '${car.id}' AND reason = 'expired'").use { it.next(); assertEquals(1, it.getInt(1)) }
            }
        }
    }
}
