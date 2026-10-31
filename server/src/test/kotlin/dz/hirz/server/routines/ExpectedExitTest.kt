package dz.hirz.server.routines

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
import dz.hirz.shared.api.OutingRequest
import dz.hirz.shared.api.PairResponse
import dz.hirz.shared.api.PlaceDto
import dz.hirz.shared.api.PlaceKind
import dz.hirz.shared.api.RoutineRequest
import dz.hirz.shared.api.RoutineTemplate
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

private val DAR = LatLon(36.7538, 3.0588)
private val ECOLE = LatLon(36.7638, 3.0588)
private fun northOf(p: LatLon, m: Double) = LatLon(p.lat + m / 111_320.0, p.lon)

private class Scholar(val circleId: String, val assetId: String, val token: String)

/** A pupil with the school routine (Sun–Thu, 08:00 to 16:30) and, if [routine], nothing else configured. */
private suspend fun HttpClient.scholar(env: TestEnv, start: Instant, routine: Boolean = true): Scholar {
    env.clock.now = start
    val circle = newCircle("test.mom")
    val school: PlaceDto = postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Ecole", PlaceKind.SCHOOL, ShapeDto.Circle(ECOLE, 80.0))).body()
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Dar", PlaceKind.HOME, ShapeDto.Circle(DAR, 50.0)))
    val kid = newAsset("test.mom", circle.id)
    if (routine) postJson("/v1/assets/${kid.id}/routines", "test.mom", RoutineRequest(school.id, template = RoutineTemplate.SCHOOL))
    val p: PairResponse = pair(pairingCode("test.mom", kid.id).code).body()
    return Scholar(circle.id, kid.id, p.sourceToken)
}

/** Fixes a minute apart: inside [from], then walking away north 200, 260, 320 m. */
private suspend fun HttpClient.leave(env: TestEnv, s: Scholar, from: LatLon) = listOf(0.0, 0.0, 200.0, 260.0, 320.0).forEach { m ->
    postJson("/v1/ingest", s.token, IngestBatch(listOf(IngestSignal("x${env.clock.millis()}", env.clock.millis(), northOf(from, m), 10.0))))
    env.clock.now = env.clock.instant().plusSeconds(60)
}

private suspend fun HttpClient.exits(s: Scholar): List<IncidentDto> =
    get("/v1/circles/${s.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body<List<IncidentDto>>().filter { it.rule == RuleId.PLACE_EXIT }

class ExpectedExitTest {
    @Test
    fun anExitConfirmedLateIsJudgedWhenTheTripBegan() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.scholar(env, Instant.parse("2026-11-08T15:35:00Z")) // 16:35
            listOf("15:35" to 0.0, "15:36" to 0.0, "15:50" to 200.0, "16:05" to 260.0).forEach { (hm, m) ->
                env.clock.now = Instant.parse("2026-11-08T$hm:00Z")
                client.postJson("/v1/ingest", s.token, IngestBatch(listOf(IngestSignal("d$hm", env.clock.millis(), northOf(ECOLE, m), 10.0))))
            }
            env.clock.now = Instant.parse("2026-11-09T06:15:00Z") // the phone is back the next morning
            client.postJson("/v1/ingest", s.token, IngestBatch(listOf(IngestSignal("next", env.clock.millis(), northOf(ECOLE, 320.0), 10.0))))
            assertTrue(client.exits(s).isEmpty(), "the walk home began after dismissal")
        }
    }

    @Test
    fun anOutingMutesPlaceExits() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.scholar(env, Instant.parse("2026-11-13T13:00:00Z"), routine = false) // Friday 14:00
            client.postJson("/v1/assets/${s.assetId}/outings", "test.mom", OutingRequest(hours = 3))
            client.leave(env, s, DAR)
            assertTrue(client.exits(s).isEmpty(), "the child is out with mum")
        }
    }

    @Test
    fun leavingSchoolAfterDismissalIsExpected() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.scholar(env, Instant.parse("2026-11-08T15:35:00Z")) // 16:35 Algiers
            client.leave(env, s, ECOLE)
            assertTrue(client.exits(s).isEmpty(), "home time: the routine watches the walk home")
        }
    }

    @Test
    fun leavingHomeForSchoolIsExpected() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.scholar(env, Instant.parse("2026-11-08T06:20:00Z")) // 07:20 Algiers
            client.leave(env, s, DAR)
            assertTrue(client.exits(s).isEmpty())
        }
    }

    @Test
    fun leavingSchoolMidMorningStillRings() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.scholar(env, Instant.parse("2026-11-08T09:00:00Z")) // 10:00 Algiers
            client.leave(env, s, ECOLE)
            assertTrue(client.exits(s).isNotEmpty(), "out of school in class time")
        }
    }

    @Test
    fun withoutARoutineEveryExitRingsAsBefore() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.scholar(env, Instant.parse("2026-11-08T15:35:00Z"), routine = false)
            client.leave(env, s, ECOLE)
            assertTrue(client.exits(s).isNotEmpty())
        }
    }

    @Test
    fun onAFridayTheRoutineDoesNotExcuseAnExit() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val s = client.scholar(env, Instant.parse("2026-11-13T06:20:00Z")) // Friday 07:20
            client.leave(env, s, DAR)
            assertTrue(client.exits(s).isNotEmpty())
        }
    }
}
