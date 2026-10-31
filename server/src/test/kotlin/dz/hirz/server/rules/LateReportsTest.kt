package dz.hirz.server.rules

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.jobs.SilenceScanner
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
import dz.hirz.shared.api.RuleOverride
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

private val PASTURE = LatLon(35.2, 4.5)
private fun north(m: Double) = LatLon(PASTURE.lat + m / 111_320.0, PASTURE.lon)

private class Animal(val circleId: String, val assetId: String, val token: String)

/** A pasture (300 m) and one asset of [type] on a phone collar, its place exit raised to P0. */
private suspend fun HttpClient.animal(type: AssetType = AssetType.LIVESTOCK): Animal {
    val circle = newCircle("test.mom")
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Mra", PlaceKind.PASTURE, ShapeDto.Circle(PASTURE, 300.0), marginM = 50))
    val a = newAsset("test.mom", circle.id, type, "Naaja")
    put("/v1/assets/${a.id}/profile") {
        bearerAuth("test.mom"); contentType(ContentType.Application.Json)
        setBody(ProfileUpdate(overrides = listOf(RuleOverride(RuleId.PLACE_EXIT, tier = Tier.P0))))
    }
    val p: PairResponse = pair(pairingCode("test.mom", a.id).code).body()
    return Animal(circle.id, a.id, p.sourceToken)
}

/** Five fixes from [startMs], 10 s apart: inside, inside, then three 600+ m out — a confirmed exit, in [order]. */
private fun exitTrack(startMs: Long, order: List<Int> = (0..4).toList()): IngestBatch {
    val metres = listOf(0.0, 0.0, 600.0, 610.0, 620.0)
    return IngestBatch(order.map { i -> IngestSignal("late$i", startMs + i * 10_000L, north(metres[i]), 10.0) })
}

private suspend fun HttpClient.open(a: Animal): List<IncidentDto> = get("/v1/circles/${a.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

class LateReportsTest {
    @Test
    fun lateExitSaysHowLateAndCapsAtP1() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val a = client.animal()
            client.postJson("/v1/ingest", a.token, exitTrack(env.clock.millis() - 5 * 3_600_000L))
            val hit = client.open(a).single()
            assertEquals(RuleId.PLACE_EXIT, hit.rule)
            assertEquals(Tier.P1, hit.tier, "five hours later, a P0 cannot ring as if it were now")
            val late = hit.reasons.first { it.code == "left_place" }.args["lateMin"]?.toInt() ?: error("no lateMin in ${hit.reasons}")
            assertTrue(late in 299..301, "reported $late min late")
        }
    }

    @Test
    fun lateFixesAreJudgedInOrder() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val a = client.animal()
            client.postJson("/v1/ingest", a.token, exitTrack(env.clock.millis() - 2 * 3_600_000L, listOf(3, 0, 4, 2, 1)))
            assertEquals(listOf(RuleId.PLACE_EXIT), client.open(a).map { it.rule })
        }
    }

    @Test
    fun peopleStillIgnoreStaleFixes() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val a = client.animal(AssetType.CHILD)
            client.postJson("/v1/ingest", a.token, exitTrack(env.clock.millis() - 5 * 3_600_000L))
            assertTrue(client.open(a).isEmpty(), "a child's five-hour-old exit is history, not an alarm")
        }
    }

    @Test
    fun livestockSilenceWaits6h() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val a = client.animal()
            client.postJson("/v1/ingest", a.token, IngestBatch(listOf(IngestSignal("one", env.clock.millis(), PASTURE, 10.0))))
            val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
            val scanner = SilenceScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, circles)))
            env.clock.advance(2.hours)
            scanner.scanOnce()
            assertTrue(client.open(a).isEmpty(), "two hours without coverage in a pasture is normal")
            env.clock.advance(5.hours)
            scanner.scanOnce()
            assertEquals(listOf(RuleId.SILENT_SOURCE), client.open(a).map { it.rule })
        }
    }

    @Test
    fun aFewMinutesLateKeepsP0() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val a = client.animal()
            client.postJson("/v1/ingest", a.token, exitTrack(env.clock.millis() - 5 * 60_000L))
            assertEquals(Tier.P0, client.open(a).single().tier, "a collar that uploads every five minutes is live, not late")
        }
    }
}
