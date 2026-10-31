package dz.hirz.server.calendar

import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.jobs.RoutineScanner
import dz.hirz.server.rules.FindingRecorder
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
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.ProfileUpdate
import dz.hirz.shared.api.RoutineRequest
import dz.hirz.shared.api.RoutineTemplate
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
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
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val HOME = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(HOME.lat + m / 111_320.0, HOME.lon)

private class Subject(val circleId: String, val assetId: String, val token: String)

/** One asset of [type] with a home (or barn for livestock) at HOME; the clock at [at]; two fixes at home. */
private suspend fun HttpClient.subject(env: TestEnv, type: AssetType, at: Instant, profile: ProfileUpdate? = null): Subject {
    env.clock.now = at
    val circle = newCircle("test.mom")
    val kind = if (type == AssetType.LIVESTOCK) PlaceKind.BARN else PlaceKind.HOME
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Dar", kind, ShapeDto.Circle(HOME, 40.0), marginM = 50))
    val a = newAsset("test.mom", circle.id, type, "X")
    if (profile != null) put("/v1/assets/${a.id}/profile") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(profile) }
    val adult = type == AssetType.ELDER
    val p: PairResponse = pair(pairingCode("test.mom", a.id).code, adult = adult, consent = adult).body()
    return Subject(circle.id, a.id, p.sourceToken).also { s -> walk(env, s, 0.0, 0.0) }
}

private suspend fun HttpClient.walk(env: TestEnv, s: Subject, vararg metres: Double) = metres.forEach { m ->
    postJson("/v1/ingest", s.token, IngestBatch(listOf(IngestSignal("s${env.clock.millis()}", env.clock.millis(), north(m), 8.0))))
    env.clock.advance(10.seconds)
}

private suspend fun HttpClient.open(s: Subject): List<IncidentDto> = get("/v1/circles/${s.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

class SunAndRamadanTest {
    private fun near(expected: Instant, got: Instant, minutes: Long = 8) =
        assertTrue(abs(java.time.Duration.between(expected, got).toMinutes()) <= minutes, "expected about $expected, got $got")

    @Test
    fun sunriseAndSunsetInAlgiers() {
        val june = SolarTimes.of(LocalDate.parse("2026-06-21"), HOME)
        near(Instant.parse("2026-06-21T04:29:00Z"), june.sunrise)
        near(Instant.parse("2026-06-21T19:12:00Z"), june.sunset)
        val december = SolarTimes.of(LocalDate.parse("2026-12-21"), HOME)
        near(Instant.parse("2026-12-21T07:01:00Z"), december.sunrise)
        near(Instant.parse("2026-12-21T16:32:00Z"), december.sunset)
    }

    @Test
    fun livestockNightFollowsTheSun() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            // 21:00 Algiers on 21 June: after sunset (about 20:12), before the old fixed 22:00.
            val s = client.subject(env, AssetType.LIVESTOCK, Instant.parse("2026-06-21T20:00:00Z"))
            client.walk(env, s, 200.0, 260.0)
            assertTrue(RuleId.NIGHT_EXIT in client.open(s).map { it.rule }, "dusk is night for a flock")
        }
    }

    @Test
    fun tarawihIsNotANightExitInRamadan() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.subject(env, AssetType.ELDER, Instant.parse("2027-02-20T21:30:00Z")) // 22:30, Ramadan 1448
            client.walk(env, s, 200.0, 260.0)
            assertTrue(client.open(s).none { it.rule == RuleId.NIGHT_EXIT }, "the tarawih prayer, every Ramadan night")
            val after = TestEnv()
            withApp(after) { c2, _ ->
                val t = c2.subject(after, AssetType.ELDER, Instant.parse("2027-04-20T21:30:00Z"))
                c2.walk(after, t, 200.0, 260.0)
                assertTrue(c2.open(t).any { it.rule == RuleId.NIGHT_EXIT }, "outside Ramadan the same walk is a night exit")
            }
        }
    }

    @Test
    fun ownerCanTurnTheRamadanExceptionOff() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.subject(env, AssetType.ELDER, Instant.parse("2027-02-20T21:30:00Z"), ProfileUpdate(ramadanNightOff = true))
            client.walk(env, s, 200.0, 260.0)
            assertTrue(client.open(s).any { it.rule == RuleId.NIGHT_EXIT })
        }
    }

    @Test
    fun ramadanShiftsTheSchoolRoutine() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            env.clock.now = Instant.parse("2027-02-21T06:30:00Z") // Sunday 07:30, Ramadan
            val circle = client.newCircle("test.mom")
            val school: PlaceDto = client.postJson("/v1/circles/${circle.id}/places", "test.mom",
                CreatePlaceRequest("Ecole", PlaceKind.SCHOOL, ShapeDto.Circle(north(1000.0), 80.0))).body()
            val kid = client.newAsset("test.mom", circle.id)
            client.postJson("/v1/assets/${kid.id}/routines", "test.mom", RoutineRequest(school.id, template = RoutineTemplate.SCHOOL, ramadanOffsetMin = 60))
            val p: PairResponse = client.pair(client.pairingCode("test.mom", kid.id).code).body()
            val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
            val scanner = RoutineScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, circles)))
            suspend fun home() = client.postJson("/v1/ingest", p.sourceToken, IngestBatch(listOf(IngestSignal("h${env.clock.millis()}", env.clock.millis(), HOME, 8.0))))
            home()
            env.clock.now = Instant.parse("2027-02-21T07:11:00Z"); home(); scanner.scanOnce()
            val open = client.get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>()
            assertTrue(open.isEmpty(), "school starts an hour later in Ramadan")
            env.clock.now = Instant.parse("2027-02-21T08:11:00Z"); home(); scanner.scanOnce()
            val later = client.get("/v1/circles/${circle.id}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>()
            assertEquals(listOf(RuleId.MISSED_ARRIVAL), later.map { it.rule })
        }
    }
}
