package dz.hirz.server.routines

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val HOME = LatLon(36.7538, 3.0588)
private val SCHOOL = LatLon(36.7638, 3.0588)
private val CORNER = LatLon(36.7638, 3.0648) // about 540 m east of the school

private class Pupil(val circleId: String, val token: String)

/** A pupil with the school routine (08:00–16:30) on Sunday 2026-11-08; optionally grandma's house at the corner. */
private suspend fun HttpClient.pupil(env: TestEnv, start: Instant, grandmaAtCorner: Boolean = false): Pupil {
    env.clock.now = start
    val circle = newCircle("test.mom")
    val school: PlaceDto = postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Ecole", PlaceKind.SCHOOL, ShapeDto.Circle(SCHOOL, 80.0))).body()
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Dar", PlaceKind.HOME, ShapeDto.Circle(HOME, 50.0)))
    if (grandmaAtCorner) postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Jedda", PlaceKind.RELATIVE, ShapeDto.Circle(CORNER, 50.0)))
    val kid = newAsset("test.mom", circle.id)
    postJson("/v1/assets/${kid.id}/routines", "test.mom", RoutineRequest(school.id, template = RoutineTemplate.SCHOOL))
    val p: PairResponse = pair(pairingCode("test.mom", kid.id).code).body()
    return Pupil(circle.id, p.sourceToken)
}

/** Fixes at [where] (metres east of it per step from [stepM]) every 5 min from [from], [n] of them, accuracy [acc]. */
private suspend fun HttpClient.track(env: TestEnv, p: Pupil, from: Instant, where: LatLon, n: Int, stepM: Double = 0.0, acc: Double = 10.0) {
    repeat(n) { i ->
        env.clock.now = from.plusSeconds(300L * i)
        val at = LatLon(where.lat, where.lon + i * stepM / (111_320.0 * 0.8014))
        postJson("/v1/ingest", p.token, IngestBatch(listOf(IngestSignal("t${env.clock.millis()}", env.clock.millis(), at, acc))))
    }
}

private suspend fun HttpClient.open(p: Pupil): List<IncidentDto> = get("/v1/circles/${p.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

private fun scanner(env: TestEnv): RoutineScanner {
    val circles = CircleService(env.db, env.clock, CircleEvents.NONE)
    return RoutineScanner(env.db, env.clock, env.rules, FindingRecorder(env.db, env.clock, IncidentService(env.db, env.clock, env.notifier, circles)))
}

// Dismissal 16:30 Algiers = 15:30Z. From 16:35 the child is watched for 15 min standing still somewhere unknown.
private val AFTER_SCHOOL = Instant.parse("2026-11-08T15:36:00Z")

class AfterSchoolTest {
    @Test
    fun stoppedAtAnUnknownCornerAfterSchool() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pupil(env, AFTER_SCHOOL)
            client.track(env, p, AFTER_SCHOOL, CORNER, n = 5)
            scanner(env).scanOnce()
            assertEquals(listOf(RuleId.STATIONARY_AFTER_DISMISSAL to Tier.P1), client.open(p).map { it.rule to it.tier })
        }
    }

    @Test
    fun waitingAtTheSchoolGateIsFine() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pupil(env, AFTER_SCHOOL)
            client.track(env, p, AFTER_SCHOOL, SCHOOL, n = 5)
            scanner(env).scanOnce()
            assertTrue(client.open(p).isEmpty(), "waiting to be picked up")
        }
    }

    @Test
    fun atGrandmasIsFine() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pupil(env, AFTER_SCHOOL, grandmaAtCorner = true)
            client.track(env, p, AFTER_SCHOOL, CORNER, n = 5)
            scanner(env).scanOnce()
            assertTrue(client.open(p).isEmpty())
        }
    }

    @Test
    fun slowWalkHomeIsNotStationary() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pupil(env, AFTER_SCHOOL)
            client.track(env, p, AFTER_SCHOOL, CORNER, n = 5, stepM = 120.0)
            scanner(env).scanOnce()
            assertTrue(client.open(p).isEmpty())
        }
    }

    @Test
    fun coarseFixesSayNothing() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val p = client.pupil(env, AFTER_SCHOOL)
            client.track(env, p, AFTER_SCHOOL, CORNER, n = 5, acc = 150.0)
            scanner(env).scanOnce()
            assertTrue(client.open(p).isEmpty(), "a cell fix cannot tell a corner from a classroom")
        }
    }

    @Test
    fun unusualStopOnTheWayIsP2() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val morning = Instant.parse("2026-11-08T06:15:00Z") // 07:15, on the way to school
            val p = client.pupil(env, morning)
            client.track(env, p, morning, CORNER, n = 4)
            scanner(env).scanOnce()
            assertEquals(listOf(RuleId.UNUSUAL_STOP to Tier.P2), client.open(p).map { it.rule to it.tier })
        }
    }
}
