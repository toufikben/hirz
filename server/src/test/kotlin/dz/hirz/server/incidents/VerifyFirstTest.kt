package dz.hirz.server.incidents

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
import dz.hirz.shared.api.PushTokenDto
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.ShapeDto
import dz.hirz.shared.signal.AlarmKind
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val SCHOOL = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(SCHOOL.lat + m / 111_320.0, SCHOOL.lon)

private class Family(val circleId: String, val assetId: String, val token: String)

private suspend fun HttpClient.family(): Family {
    val circle = newCircle("test.mom")
    put("/v1/me/push-token") { bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(PushTokenDto("tok-mom")) }
    postJson("/v1/circles/${circle.id}/places", "test.mom", CreatePlaceRequest("Ecole", PlaceKind.SCHOOL, ShapeDto.Circle(SCHOOL, 100.0), marginM = 50))
    val kid = newAsset("test.mom", circle.id)
    val p: PairResponse = pair(pairingCode("test.mom", kid.id).code).body()
    return Family(circle.id, kid.id, p.sourceToken)
}

/** [n] past incidents of [rule] on the asset, closed and reviewed as false alarms by mom [daysAgo] days ago. */
private fun history(env: TestEnv, f: Family, rule: String, tier: String, n: Int, daysAgo: Int = 3) = env.db.connection.use { c ->
    val mom = c.createStatement().executeQuery("SELECT member_id FROM circle_members WHERE circle_id = '${f.circleId}' AND role = 'owner'")
        .use { it.next(); it.getString(1) }
    val when_ = env.clock.instant().minusSeconds(daysAgo * 86_400L)
    repeat(n) {
        c.createStatement().executeUpdate(
            """WITH i AS (INSERT INTO incidents (asset_id, circle_id, rule, tier, state, opened_at, closed_at)
                 VALUES ('${f.assetId}', '${f.circleId}', '$rule', '$tier', 'closed', '$when_'::timestamptz, '$when_'::timestamptz) RETURNING id)
               INSERT INTO incident_reviews (incident_id, member_id, false_alarm, note, at)
               SELECT id, '$mom', true, null, '$when_'::timestamptz FROM i""",
        )
    }
}

private suspend fun HttpClient.leaveSchool(env: TestEnv, f: Family) = listOf(0.0, 0.0, 200.0, 210.0, 220.0).forEach { m ->
    postJson("/v1/ingest", f.token, IngestBatch(listOf(IngestSignal("x${env.clock.millis()}", env.clock.millis(), north(m), 10.0))))
    env.clock.advance(10.seconds)
}

private suspend fun HttpClient.open(f: Family): List<IncidentDto> = get("/v1/circles/${f.circleId}/incidents?state=open") { bearerAuth("test.mom") }.body()

class VerifyFirstTest {
    @Test
    fun threeFalseAlarmsMakeP1VerifyFirst() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            history(env, f, "PLACE_EXIT", "P1", 3)
            client.leaveSchool(env, f)
            val exit = client.open(f).single { it.rule == RuleId.PLACE_EXIT }
            assertTrue(exit.verifyFirst, "three false exits this month: check first, quietly")
            assertTrue(env.notifier.sent.any { it.second.kind == "incident_quiet" })
            assertTrue(env.notifier.sent.none { it.second.kind == "incident" && it.second.incidentId.toString() == exit.id }, "no ringing first push")
        }
    }

    @Test
    fun historyLowersP1NotP0() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            history(env, f, "SOS", "P0", 5)
            client.postJson("/v1/ingest", f.token, IngestBatch(listOf(IngestSignal("sos", alarms = setOf(AlarmKind.SOS)))))
            val sos = client.open(f).single { it.rule == RuleId.SOS }
            assertFalse(sos.verifyFirst, "an SOS always rings")
        }
    }

    @Test
    fun verifyFirstStillEscalates() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            history(env, f, "PLACE_EXIT", "P1", 3)
            client.leaveSchool(env, f)
            val exit = client.open(f).single { it.rule == RuleId.PLACE_EXIT }
            val detail: IncidentDto = client.get("/v1/incidents/${exit.id}") { bearerAuth("test.mom") }.body()
            assertNotNull(detail.escalation, "an unconfirmed check escalates like any P1")
        }
    }

    @Test
    fun ownerCanUndo() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            history(env, f, "PLACE_EXIT", "P1", 3)
            client.put("/v1/assets/${f.assetId}/profile") {
                bearerAuth("test.mom"); contentType(ContentType.Application.Json); setBody(ProfileUpdate(verifyFirstOff = true))
            }
            client.leaveSchool(env, f)
            assertFalse(client.open(f).single { it.rule == RuleId.PLACE_EXIT }.verifyFirst)
        }
    }

    @Test
    fun oldReviewsExpire() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            history(env, f, "PLACE_EXIT", "P1", 3, daysAgo = 40)
            client.leaveSchool(env, f)
            assertFalse(client.open(f).single { it.rule == RuleId.PLACE_EXIT }.verifyFirst)
        }
    }

    @Test
    fun profileShowsVerifyFirstRules() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val f = client.family()
            history(env, f, "PLACE_EXIT", "P1", 3)
            val p: dz.hirz.shared.api.ProfileDto = client.get("/v1/assets/${f.assetId}/profile") { bearerAuth("test.mom") }.body()
            assertEquals(listOf(RuleId.PLACE_EXIT), p.verifyFirst)
        }
    }
}
