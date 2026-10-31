package dz.hirz.server.trials

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.join
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CONSENT_VERSION
import dz.hirz.shared.api.CreateTrialRequest
import dz.hirz.shared.api.GuidedTestRequest
import dz.hirz.shared.api.JoinTrialRequest
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.TrialDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "test.ops" is the trial's operator (HIRZ_OPERATORS in staging). */
private suspend fun HttpClient.openTrial(): TrialDto =
    postJson("/v1/operator/trials", "test.ops", CreateTrialRequest("Blida pilot", "2026-11-01", "2026-11-28")).body()

private fun enrolled(env: TestEnv, circleId: String): Int = env.db.connection.use { c ->
    c.createStatement().executeQuery("SELECT count(*) FROM trial_circles WHERE circle_id = '$circleId' AND left_at IS NULL").use { it.next(); it.getInt(1) }
}

class TrialTest {
    @Test
    fun ownerJoinsWithConsent() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val trial = client.openTrial()
            val circle = client.newCircle("test.mom")
            val open: List<TrialDto> = client.get("/v1/trials/open") { bearerAuth("test.mom") }.body()
            assertEquals(listOf(trial.id), open.map { it.id })
            assertEquals(HttpStatusCode.UnprocessableEntity,
                client.postJson("/v1/circles/${circle.id}/trial", "test.mom", JoinTrialRequest(trial.id, CONSENT_VERSION - 1)).status, "an old consent text")
            assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/circles/${circle.id}/trial", "test.mom", JoinTrialRequest(trial.id, CONSENT_VERSION)).status)
            assertEquals(1, enrolled(env, circle.id))
        }
    }

    @Test
    fun guardianCannotEnrol() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val trial = client.openTrial()
            val circle = client.newCircle("test.mom")
            client.join("test.mom", circle.id, "test.dad", Role.GUARDIAN)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/circles/${circle.id}/trial", "test.dad", JoinTrialRequest(trial.id, CONSENT_VERSION)).status)
            assertEquals(0, enrolled(env, circle.id))
        }
    }

    @Test
    fun leavingDeletesTrialMetrics() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val trial = client.openTrial()
            val circle = client.newCircle("test.mom")
            client.postJson("/v1/circles/${circle.id}/trial", "test.mom", JoinTrialRequest(trial.id, CONSENT_VERSION))
            env.db.connection.use { c ->
                c.createStatement().executeUpdate(
                    """INSERT INTO trial_daily (trial_id, circle_pseudo, asset_pseudo, day)
                       SELECT trial_id, pseudonym, 'a1', DATE '2026-11-01' FROM trial_circles WHERE circle_id = '${circle.id}'""",
                )
            }
            assertEquals(HttpStatusCode.NoContent, client.delete("/v1/circles/${circle.id}/trial") { bearerAuth("test.mom") }.status)
            assertEquals(0, enrolled(env, circle.id))
            val left = env.db.connection.use { c -> c.createStatement().executeQuery("SELECT count(*) FROM trial_daily").use { it.next(); it.getInt(1) } }
            assertEquals(0, left, "leaving takes the circle's trial data with it")
        }
    }

    @Test
    fun operatorAllowListOnly() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            assertEquals(HttpStatusCode.Forbidden,
                client.postJson("/v1/operator/trials", "test.mom", CreateTrialRequest("x", "2026-11-01", "2026-11-28")).status)
            assertEquals(HttpStatusCode.UnprocessableEntity,
                client.postJson("/v1/operator/trials", "test.ops", CreateTrialRequest("x", "2026-11-28", "2026-11-01")).status, "ends before it starts")
        }
    }

    @Test
    fun trialEndsByItself() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val trial = client.openTrial()
            val circle = client.newCircle("test.mom")
            val kid = client.newAsset("test.mom", circle.id)
            client.postJson("/v1/circles/${circle.id}/trial", "test.mom", JoinTrialRequest(trial.id, CONSENT_VERSION))
            assertEquals(HttpStatusCode.OK, client.postJson("/v1/circles/${circle.id}/trial/tests", "test.mom", GuidedTestRequest(kid.id, RuleId.SOS)).status)
            env.clock.now = Instant.parse("2026-11-29T10:00:00Z")
            assertTrue(client.get("/v1/trials/open") { bearerAuth("test.mom") }.body<List<TrialDto>>().isEmpty())
            assertEquals(HttpStatusCode.Conflict, client.postJson("/v1/circles/${circle.id}/trial/tests", "test.mom", GuidedTestRequest(kid.id, RuleId.SOS)).status)
        }
    }
}
