package dz.hirz.server.rules

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.jobs.HerdScanner
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.CreateHerdRequest
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.HerdDto
import dz.hirz.shared.api.HerdMemberRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.LeaderRequest
import dz.hirz.shared.api.MarketRequest
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.signal.AlarmKind
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val BARN = LatLon(35.2, 4.5)
private fun at(north: Double, east: Double = 0.0) = LatLon(BARN.lat + north / 111_320.0, BARN.lon + east / (111_320.0 * 0.8166))

private class Herd(val circleId: String, val herdId: String, val tokens: List<String>)

/** A barn (40 m) and pasture (1 km) and a herd of [size] on phone collars, the first one leading; the clock at [start]. */
private suspend fun HttpClient.herd(env: TestEnv, start: Instant, size: Int = 3): Herd {
    env.clock.now = start
    val circle = newCircle("test.mom")
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Zriba", PlaceKind.BARN, ShapeDto.Circle(BARN, 40.0), marginM = 50))
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Mra", PlaceKind.PASTURE, ShapeDto.Circle(at(3000.0), 1000.0), marginM = 50))
    val herd: HerdDto = postJson("/v1/circles/${circle.id}/herds", "test.mom", CreateHerdRequest("Ouled Djellal")).body()
    val tokens = (1..size).map { i ->
        val a = newAsset("test.mom", circle.id, AssetType.LIVESTOCK, "Head $i")
        postJson("/v1/herds/${herd.id}/members", "test.mom", HerdMemberRequest(a.id))
        if (i == 1) put("/v1/herds/${herd.id}/leader") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(LeaderRequest(a.id)) }
        pair(pairingCode("test.mom", a.id).code).body<PairResponse>().sourceToken
    }
    return Herd(circle.id, herd.id, tokens)
}

private suspend fun HttpClient.fix(env: TestEnv, token: String, p: LatLon, speed: Double? = null, acc: Double = 8.0) {
    postJson("/v1/ingest", token, IngestBatch(listOf(IngestSignal("h${env.clock.millis()}${token.take(4)}", env.clock.millis(), p, acc, speedKmh = speed))))
}

private suspend fun HttpClient.open(h: Herd): List<IncidentDto> = get("/v1/circles/${h.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

private fun scanner(env: TestEnv): HerdScanner {
    val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
    return HerdScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, circles)))
}

class HerdRulesTest {
    @Test
    fun herdLeavesBarnAt2am() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.herd(env, Instant.parse("2026-11-02T01:00:00Z"))
            repeat(2) { h.tokens.forEach { t -> client.fix(env, t, BARN) }; env.clock.advance(10.seconds) }
            listOf(200.0, 260.0).forEach { m -> client.fix(env, h.tokens[0], at(m)); env.clock.advance(10.seconds) }
            val rules = client.open(h).map { it.rule }.toSet()
            assertTrue(RuleId.NIGHT_EXIT in rules && RuleId.HERD_NIGHT_MOVE in rules, "the leader out of the barn at 02:00: $rules")
        }
    }

    @Test
    fun marketDayAuthorizesMovement() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.herd(env, Instant.parse("2026-11-03T06:00:00Z"), size = 1) // a Tuesday, 07:00 Algiers
            client.put("/v1/herds/${h.herdId}/market") {
                bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(MarketRequest(listOf(ActiveWindow(setOf(2), 5 * 60, 14 * 60))))
            }
            (0..8).forEach { i -> client.fix(env, h.tokens[0], at(5000.0 + i * 110.0), speed = 40.0); env.clock.advance(10.seconds) }
            assertTrue(client.open(h).none { it.rule == RuleId.VEHICLE_SPEED_UNEXPLAINED }, "taking the herd to the souk")
        }
    }

    @Test
    fun loadedOnATruck() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.herd(env, Instant.parse("2026-11-02T09:00:00Z"), size = 1)
            (0..8).forEach { i -> client.fix(env, h.tokens[0], at(5000.0 + i * 110.0), speed = 40.0); env.clock.advance(10.seconds) }
            assertEquals(Tier.P1, client.open(h).single { it.rule == RuleId.VEHICLE_SPEED_UNEXPLAINED }.tier)
        }
    }

    @Test
    fun collarCutAlarm() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.herd(env, Instant.parse("2026-11-02T09:00:00Z"), size = 1)
            client.postJson("/v1/ingest", h.tokens[0], IngestBatch(listOf(IngestSignal("cut", alarms = setOf(AlarmKind.REMOVAL)))))
            assertEquals(listOf(RuleId.COLLAR_CUT to Tier.P1), client.open(h).map { it.rule to it.tier })
        }
    }

    @Test
    fun wolvesScatterTheHerd() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.herd(env, Instant.parse("2026-11-02T09:00:00Z"), size = 4)
            val tight = listOf(at(3000.0), at(3010.0), at(3000.0, 10.0), at(3010.0, 10.0))
            h.tokens.zip(tight).forEach { (t, p) -> client.fix(env, t, p) }
            scanner(env).scanOnce()
            env.clock.advance(5.minutes)
            val wide = listOf(at(3000.0), at(3500.0), at(3000.0, 500.0), at(2600.0, -400.0))
            h.tokens.zip(wide).forEach { (t, p) -> client.fix(env, t, p) }
            scanner(env).scanOnce()
            assertEquals(Tier.P2, client.open(h).single { it.rule == RuleId.HERD_DISPERSION }.tier, "scattered by day")
        }
    }

    @Test
    fun grazingSpreadIsNotDispersion() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.herd(env, Instant.parse("2026-11-02T09:00:00Z"), size = 4)
            h.tokens.zip(listOf(at(3000.0), at(3010.0), at(3000.0, 10.0), at(3010.0, 10.0))).forEach { (t, p) -> client.fix(env, t, p) }
            scanner(env).scanOnce()
            env.clock.advance(5.minutes)
            h.tokens.zip(listOf(at(3000.0), at(3120.0), at(3000.0, 120.0), at(2900.0, -80.0))).forEach { (t, p) -> client.fix(env, t, p) }
            scanner(env).scanOnce()
            assertTrue(client.open(h).none { it.rule == RuleId.HERD_DISPERSION })
        }
    }

    @Test
    fun sickEweStillFor6h() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.herd(env, Instant.parse("2026-11-02T08:00:00Z"), size = 1) // 09:00 Algiers
            repeat(14) { i ->
                client.fix(env, h.tokens[0], at(3000.0 + (i % 3), 2.0 * (i % 2)))
                env.clock.advance(30.minutes)
            }
            scanner(env).scanOnce()
            assertEquals(Tier.P2, client.open(h).single { it.rule == RuleId.ANIMAL_STILL }.tier)
        }
    }

    @Test
    fun noFixesIsSilenceNotStillness() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.herd(env, Instant.parse("2026-11-02T08:00:00Z"), size = 1)
            client.fix(env, h.tokens[0], at(3000.0))
            env.clock.advance((6 * 60 + 30).minutes)
            client.fix(env, h.tokens[0], at(3000.0))
            scanner(env).scanOnce()
            assertTrue(client.open(h).none { it.rule == RuleId.ANIMAL_STILL }, "two fixes say nothing about six hours")
        }
    }
}
