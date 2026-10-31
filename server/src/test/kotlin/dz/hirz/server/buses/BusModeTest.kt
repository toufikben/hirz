package dz.hirz.server.buses

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.BusRouteDto
import dz.hirz.shared.api.BusRouteRequest
import dz.hirz.shared.api.BusStopRequest
import dz.hirz.shared.api.CircleDto
import dz.hirz.shared.api.CircleKind
import dz.hirz.shared.api.CreateCircleRequest
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.ModeRequest
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.api.VehicleStateDto
import dz.hirz.shared.asset.AssetType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

private val DEPOT = LatLon(36.7400, 3.0500)
private val SCHOOL = LatLon(36.7538, 3.0588)

private class Fleet(val circleId: String, val busId: String)

/** A transport company (owner test.boss, supervisor test.sup) with one bus, Sunday 2026-11-08. */
private suspend fun HttpClient.fleet(env: TestEnv): Fleet {
    env.clock.now = Instant.parse("2026-11-08T05:00:00Z")
    val c: CircleDto = postJson("/v1/circles", "test.boss", CreateCircleRequest("Transport Blida", CircleKind.INSTITUTION)).body()
    join("test.boss", c.id, "test.sup", Role.GUARDIAN)
    join("test.boss", c.id, "test.clerk", Role.VIEWER)
    val bus = newAsset("test.boss", c.id, AssetType.BUS, "Bus 3")
    return Fleet(c.id, bus.id)
}

/** The morning run: Sunday–Thursday 06:30–08:30 Algiers, depot to school, two stops. */
private fun morning() = BusRouteRequest(
    name = "Matin", path = listOf(DEPOT, LatLon(36.7470, 3.0544), SCHOOL), widthM = 80.0,
    days = setOf(7, 1, 2, 3, 4), startMin = 6 * 60 + 30, endMin = 8 * 60 + 30,
    stops = listOf(BusStopRequest("Cité 1", LatLon(36.7450, 3.0530), dueMin = 7 * 60), BusStopRequest("Ecole", SCHOOL, dueMin = 7 * 60 + 40)),
)

private suspend fun HttpClient.mode(f: Fleet): VehicleMode =
    get("/v1/assets/${f.busId}/vehicle") { bearerAuth("test.boss") }.body<VehicleStateDto>().mode

class BusModeTest {
    @Test
    fun scheduleSwitchesRouteAndArmed() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.fleet(env)
            assertEquals(HttpStatusCode.OK, client.postJson("/v1/assets/${f.busId}/bus-routes", "test.sup", morning()).status)
            client.put("/v1/assets/${f.busId}/armed-schedule") {
                bearerAuth("test.boss"); contentType(ContentType.Application.Json)
                setBody(listOf(ActiveWindow((1..7).toSet(), 20 * 60, 1440)))
            }
            env.clock.now = Instant.parse("2026-11-08T06:00:00Z") // 07:00, in service
            assertEquals(VehicleMode.ROUTE, client.mode(f))
            env.clock.now = Instant.parse("2026-11-08T21:00:00Z") // 22:00, parked for the night
            assertEquals(VehicleMode.ARMED, client.mode(f))
            env.clock.now = Instant.parse("2026-11-13T06:00:00Z") // Friday 07:00, no service
            assertEquals(false, client.mode(f) == VehicleMode.ROUTE)
        }
    }

    @Test
    fun maintenanceWinsAndIsAudited() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.fleet(env)
            client.postJson("/v1/assets/${f.busId}/bus-routes", "test.sup", morning())
            env.clock.now = Instant.parse("2026-11-08T06:00:00Z")
            client.postJson("/v1/assets/${f.busId}/mode", "test.boss", ModeRequest(VehicleMode.MAINTENANCE))
            assertEquals(VehicleMode.MAINTENANCE, client.mode(f), "at the mechanic's, no run")
            val audited = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM audit_log WHERE action = 'vehicle_mode_set'").use { it.next(); it.getInt(1) }
            }
            assertEquals(1, audited)
        }
    }

    @Test
    fun noSwitchOutOfArmedDuringAnIncident() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.fleet(env)
            env.db.connection.use { c ->
                c.createStatement().executeUpdate(
                    "INSERT INTO incidents (asset_id, circle_id, rule, tier, state, opened_at) VALUES ('${f.busId}', '${f.circleId}', 'ARMED_MOVEMENT', 'P1', 'open', now())",
                )
            }
            assertEquals(HttpStatusCode.Conflict, client.postJson("/v1/assets/${f.busId}/mode", "test.boss", ModeRequest(VehicleMode.MAINTENANCE)).status)
        }
    }

    @Test
    fun routeNeedsOrderedStopsAndABus() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.fleet(env)
            val r = morning()
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postJson("/v1/assets/${f.busId}/bus-routes", "test.sup",
                r.copy(stops = r.stops.reversed())).status, "stops in time order")
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postJson("/v1/assets/${f.busId}/bus-routes", "test.sup",
                r.copy(path = listOf(DEPOT))).status, "a path needs two points")
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postJson("/v1/assets/${f.busId}/bus-routes", "test.sup",
                r.copy(startMin = 9 * 60)).status, "the window must start before it ends")
            val car = client.newAsset("test.boss", f.circleId, AssetType.CAR, "Car")
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postJson("/v1/assets/${car.id}/bus-routes", "test.sup", r).status)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/assets/${f.busId}/bus-routes", "test.clerk", r).status, "viewers read only")
            val list: List<BusRouteDto> = client.get("/v1/assets/${f.busId}/bus-routes") { bearerAuth("test.clerk") }.body()
            assertEquals(0, list.size)
        }
    }
}
