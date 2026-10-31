package dz.hirz.server.buses

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.jobs.BusScanner
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.BusRouteRequest
import dz.hirz.shared.api.BusStopRequest
import dz.hirz.shared.api.CircleDto
import dz.hirz.shared.api.CircleKind
import dz.hirz.shared.api.CreateCircleRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.AssetType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val A = LatLon(36.7400, 3.0500)
private val B = LatLon(36.7538, 3.0500)
private fun east(m: Double, north: Double = 700.0) = LatLon(A.lat + north / 111_320.0, A.lon + m / (111_320.0 * 0.8017))

private class Run(val circleId: String, val token: String)

/** A bus with the morning run (Sun–Thu 06:30–08:30; stops due 07:00 and 07:40) and a paired tracker. */
private suspend fun HttpClient.run(env: TestEnv, start: Instant): Run {
    env.clock.now = start
    val c: CircleDto = postJson("/v1/circles", "test.boss", CreateCircleRequest("Transport", CircleKind.INSTITUTION)).body()
    val bus = newAsset("test.boss", c.id, AssetType.BUS, "Bus 3")
    postJson("/v1/assets/${bus.id}/bus-routes", "test.boss", BusRouteRequest("Matin", listOf(A, B), 80.0, setOf(7, 1, 2, 3, 4), 390, 510,
        listOf(BusStopRequest("Cité", east(0.0, 300.0), dueMin = 420), BusStopRequest("Ecole", B, dueMin = 460))))
    val p: PairResponse = pair(pairingCode("test.boss", bus.id).code).body()
    return Run(c.id, p.sourceToken)
}

private suspend fun HttpClient.drive(env: TestEnv, r: Run, n: Int, everyS: Long, at: (Int) -> LatLon, speed: Double? = null) = repeat(n) { i ->
    postJson("/v1/ingest", r.token, IngestBatch(listOf(IngestSignal("b${env.clock.millis()}", env.clock.millis(), at(i), 10.0, speedKmh = speed))))
    env.clock.now = env.clock.instant().plusSeconds(everyS)
}

private fun scanner(env: TestEnv) =
    BusScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, CircleService(env.db, env.clock, CircleEvents.NONE))))

private suspend fun HttpClient.open(r: Run): List<IncidentDto> = get("/v1/circles/${r.circleId}/incidents?state=open") { bearerAuth("test.boss") }.body()

class BusScannerTest {
    @Test
    fun offCorridorInServiceIsP1() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val r = client.run(env, Instant.parse("2026-11-08T05:50:00Z")) // 06:50
            client.drive(env, r, 6, 20, { east(400.0) })
            scanner(env).scanOnce()
            scanner(env).scanOnce()
            val dev = client.open(r).filter { it.rule == RuleId.ROUTE_DEVIATION }
            assertEquals(listOf(Tier.P1), dev.map { it.tier }, "once per run")
        }
    }

    @Test
    fun onTheCorridorNothing() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val r = client.run(env, Instant.parse("2026-11-08T05:50:00Z"))
            client.drive(env, r, 6, 20, { i -> east(if (i % 2 == 0) 30.0 else -30.0, 300.0 + i * 100) })
            scanner(env).scanOnce()
            assertTrue(client.open(r).none { it.rule == RuleId.ROUTE_DEVIATION })
        }
    }

    @Test
    fun lateAtTheFirstStopIsP2() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val r = client.run(env, Instant.parse("2026-11-08T06:05:00Z")) // 07:05, still at the depot
            client.drive(env, r, 2, 60, { east(0.0, -50.0) })
            env.clock.now = Instant.parse("2026-11-08T06:11:00Z") // 07:11
            client.drive(env, r, 1, 0, { east(0.0, -50.0) })
            scanner(env).scanOnce()
            assertEquals(Tier.P2, client.open(r).single { it.rule == RuleId.STOP_LATE }.tier)
        }
    }

    @Test
    fun onlyInRouteMode() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val r = client.run(env, Instant.parse("2026-11-13T05:50:00Z")) // Friday: no run
            client.drive(env, r, 6, 20, { east(400.0) })
            scanner(env).scanOnce()
            assertTrue(client.open(r).none { it.rule == RuleId.ROUTE_DEVIATION || it.rule == RuleId.STOP_LATE })
        }
    }
}
