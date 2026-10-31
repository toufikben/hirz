package dz.hirz.server.routines

import dz.hirz.server.calendar.AlgerianCalendar
import dz.hirz.server.circles.CircleEvents
import dz.hirz.server.circles.CircleService
import dz.hirz.server.incidents.IncidentService
import dz.hirz.server.jobs.RoutineScanner
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.pair
import dz.hirz.server.support.pairingCode
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreatePlaceRequest
import dz.hirz.shared.api.HandoverRequest
import dz.hirz.shared.api.IncidentDto
import dz.hirz.shared.api.IngestBatch
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.MemberDto
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.RoutineRequest
import dz.hirz.shared.api.RoutineTemplate
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.api.Tier
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val HOME = LatLon(36.7538, 3.0588)
private val SCHOOL = LatLon(36.7638, 3.0588) // about 1.1 km north

private class Kid(val circleId: String, val assetId: String, val token: String)

/** A child with a school routine (08:00 arrival), paired; the clock at [start]. */
private suspend fun HttpClient.kid(env: TestEnv, start: Instant): Kid {
    env.clock.now = start
    val circle = newCircle("test.mom")
    val school: PlaceDto = postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Ecole", PlaceKind.SCHOOL, ShapeDto.Circle(SCHOOL, 80.0))).body()
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Dar", PlaceKind.HOME, ShapeDto.Circle(HOME, 50.0)))
    val kid = newAsset("test.mom", circle.id)
    postJson("/v1/assets/${kid.id}/routines", "test.mom", RoutineRequest(school.id, template = RoutineTemplate.SCHOOL))
    val p: PairResponse = pair(pairingCode("test.mom", kid.id).code).body()
    return Kid(circle.id, kid.id, p.sourceToken)
}

private suspend fun HttpClient.at(env: TestEnv, k: Kid, p: LatLon) =
    postJson("/v1/ingest", k.token, IngestBatch(listOf(IngestSignal("m${env.clock.millis()}", env.clock.millis(), p, 10.0))))

private suspend fun HttpClient.open(k: Kid): List<IncidentDto> = get("/v1/circles/${k.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

private fun scanner(env: TestEnv): RoutineScanner {
    val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
    return RoutineScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, circles)))
}

// 2026-11-08 is a Sunday school day; 08:11 Algiers = 07:11Z.
private val SUNDAY_0730 = Instant.parse("2026-11-08T06:30:00Z")
private val SUNDAY_0811 = Instant.parse("2026-11-08T07:11:00Z")

class MissedArrivalTest {
    @Test
    fun schoolCalendar() {
        assertTrue(AlgerianCalendar.isSchoolDay(LocalDate.parse("2026-11-08"), emptyList()))
        assertFalse(AlgerianCalendar.isSchoolDay(LocalDate.parse("2026-11-13"), emptyList()), "Friday")
        assertFalse(AlgerianCalendar.isSchoolDay(LocalDate.parse("2026-11-14"), emptyList()), "Saturday")
        assertFalse(AlgerianCalendar.isSchoolDay(LocalDate.parse("2026-12-24"), emptyList()), "winter holidays")
        assertFalse(AlgerianCalendar.isSchoolDay(LocalDate.parse("2027-07-05"), emptyList()), "Independence Day")
        assertFalse(AlgerianCalendar.isSchoolDay(LocalDate.parse("2026-11-08"), listOf("school_day_off" to LocalDate.parse("2026-11-08"))), "the owner knows better")
    }

    @Test
    fun notAtSchoolBy0810RaisesP1() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val k = client.kid(env, SUNDAY_0730)
            client.at(env, k, HOME)
            env.clock.now = SUNDAY_0811
            client.at(env, k, HOME)
            scanner(env).scanOnce()
            assertEquals(listOf(RuleId.MISSED_ARRIVAL to Tier.P1), client.open(k).map { it.rule to it.tier })
        }
    }

    @Test
    fun arrivedAt0755IsFine() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val k = client.kid(env, SUNDAY_0730)
            client.at(env, k, HOME)
            env.clock.now = Instant.parse("2026-11-08T06:55:00Z")
            client.at(env, k, SCHOOL)
            env.clock.now = SUNDAY_0811
            client.at(env, k, SCHOOL)
            scanner(env).scanOnce()
            assertTrue(client.open(k).isEmpty())
        }
    }

    @Test
    fun fridayNoRoutine() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val k = client.kid(env, Instant.parse("2026-11-13T06:30:00Z"))
            client.at(env, k, HOME)
            env.clock.now = Instant.parse("2026-11-13T07:11:00Z")
            client.at(env, k, HOME)
            scanner(env).scanOnce()
            assertTrue(client.open(k).isEmpty())
        }
    }

    @Test
    fun holidayNoMissedArrival() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val k = client.kid(env, Instant.parse("2026-12-24T06:30:00Z"))
            client.at(env, k, HOME)
            env.clock.now = Instant.parse("2026-12-24T07:11:00Z")
            client.at(env, k, HOME)
            scanner(env).scanOnce()
            assertTrue(client.open(k).isEmpty(), "the whole country's children are home in the winter holidays")
        }
    }

    @Test
    fun deadPhoneIsSilenceNotMissedArrival() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val k = client.kid(env, Instant.parse("2026-11-08T05:00:00Z"))
            client.at(env, k, HOME)
            env.clock.now = SUNDAY_0811
            scanner(env).scanOnce()
            assertTrue(client.open(k).none { it.rule == RuleId.MISSED_ARRIVAL }, "a phone dead since 06:00 says nothing about school")
        }
    }

    @Test
    fun handoverDayOff() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val k = client.kid(env, SUNDAY_0730)
            client.join("test.mom", k.circleId, "test.dad", Role.GUARDIAN)
            val dad = client.get("/v1/circles/${k.circleId}/members") { bearerAuth("test.mom") }.body<List<MemberDto>>().single { it.role == Role.GUARDIAN }
            client.postJson("/v1/assets/${k.assetId}/handovers", "test.mom",
                HandoverRequest(dad.memberId, env.clock.millis(), env.clock.millis() + 10 * 3_600_000L, mutePlaceExit = true))
            client.at(env, k, HOME)
            env.clock.now = SUNDAY_0811
            client.at(env, k, HOME)
            scanner(env).scanOnce()
            assertTrue(client.open(k).isEmpty(), "with dad for the day, as agreed")
        }
    }

    @Test
    fun onceADay() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val k = client.kid(env, SUNDAY_0730)
            client.at(env, k, HOME)
            env.clock.now = SUNDAY_0811
            client.at(env, k, HOME)
            scanner(env).scanOnce()
            env.clock.now = Instant.parse("2026-11-08T07:20:00Z")
            client.at(env, k, HOME)
            scanner(env).scanOnce()
            env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM findings WHERE rule = 'MISSED_ARRIVAL'").use { it.next(); assertEquals(1, it.getInt(1)) }
            }
        }
    }
}
