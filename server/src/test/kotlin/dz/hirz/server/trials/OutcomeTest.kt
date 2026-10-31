package dz.hirz.server.trials

import dz.hirz.server.incidents.VerifyFirst
import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.DigestFeedbackRequest
import dz.hirz.shared.api.Outcome
import dz.hirz.shared.api.ReviewRequest
import dz.hirz.shared.rules.RuleConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Household(val circleId: String, val assetId: String)

private suspend fun HttpClient.household(): Household {
    val circle = newCircle("test.mom")
    val kid = newAsset("test.mom", circle.id)
    return Household(circle.id, kid.id)
}

/** A closed P1 incident of [rule] on the household's asset, opened a day before the clock. */
private fun closedIncident(env: TestEnv, h: Household, rule: String = "PLACE_EXIT"): String = env.db.connection.use { c ->
    val at = env.clock.instant().minusSeconds(86_400)
    c.createStatement().executeQuery(
        """INSERT INTO incidents (asset_id, circle_id, rule, tier, state, opened_at, closed_at)
           VALUES ('${h.assetId}', '${h.circleId}', '$rule', 'P1', 'closed', '$at'::timestamptz, '$at'::timestamptz) RETURNING id""",
    ).use { it.next(); it.getString(1) }
}

private fun review(env: TestEnv, incidentId: String): Pair<String?, Boolean> = env.db.connection.use { c ->
    c.createStatement().executeQuery("SELECT outcome, false_alarm FROM incident_reviews WHERE incident_id = '$incidentId'")
        .use { it.next(); it.getString(1) to it.getBoolean(2) }
}

/** A digest of two lines for the household's circle, today. */
private fun digest(env: TestEnv, h: Household): String = env.db.connection.use { c ->
    c.createStatement().executeQuery(
        """INSERT INTO digests (circle_id, day, items) VALUES ('${h.circleId}', current_date,
           '[{"assetId":"${h.assetId}","assetName":"A","rule":"UNUSUAL_STOP","count":1,"firstAt":0},
             {"assetId":"${h.assetId}","assetName":"A","rule":"SILENT_SOURCE","count":2,"firstAt":0}]'::jsonb) RETURNING id""",
    ).use { it.next(); it.getString(1) }
}

class OutcomeTest {
    @Test
    fun reviewTakesAnOutcome() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.household()
            val id = closedIncident(env, h)
            assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/incidents/$id/review", "test.mom", ReviewRequest(outcome = Outcome.FALSE_RULE)).status)
            assertEquals("false_rule" to true, review(env, id))
        }
    }

    @Test
    fun testOutcomeIsNotAFalseAlarm() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.household()
            repeat(3) {
                val id = closedIncident(env, h)
                client.postJson("/v1/incidents/$id/review", "test.mom", ReviewRequest(outcome = Outcome.TEST))
                assertEquals("test" to false, review(env, id))
            }
            val quiet = env.db.connection.use { c -> VerifyFirst.rules(c, UUID.fromString(h.assetId), env.clock.instant(), RuleConfig.DEFAULT) }
            assertTrue(quiet.isEmpty(), "three guided tests do not make a rule check-first")
        }
    }

    @Test
    fun digestFeedbackOncePerMemberAndLine() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.household()
            val d = digest(env, h)
            assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/digests/$d/feedback", "test.mom", DigestFeedbackRequest(0, true)).status)
            assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/digests/$d/feedback", "test.mom", DigestFeedbackRequest(0, false)).status)
            client.postJson("/v1/digests/$d/feedback", "test.mom", DigestFeedbackRequest(1, true))
            val rows = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT item_index, useful FROM digest_feedback WHERE digest_id = '$d' ORDER BY item_index")
                    .use { rs -> buildList { while (rs.next()) add(rs.getInt(1) to rs.getBoolean(2)) } }
            }
            assertEquals(listOf(0 to false, 1 to true), rows, "the last answer per line counts")
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postJson("/v1/digests/$d/feedback", "test.mom", DigestFeedbackRequest(2, true)).status)
        }
    }

    @Test
    fun pendingReviewsAreThoseIHaveNotAnswered() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.household()
            val a = closedIncident(env, h)
            val b = closedIncident(env, h)
            client.postJson("/v1/incidents/$a/review", "test.mom", ReviewRequest(outcome = Outcome.REAL))
            val pending: List<dz.hirz.shared.api.IncidentDto> =
                client.get("/v1/circles/${h.circleId}/reviews/pending") { bearerAuth("test.mom") }.body()
            assertEquals(listOf(b), pending.map { it.id })
        }
    }

    @Test
    fun strangerCannotReview() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val h = client.household()
            val id = closedIncident(env, h)
            val d = digest(env, h)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/incidents/$id/review", "test.eve", ReviewRequest(outcome = Outcome.REAL)).status)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/digests/$d/feedback", "test.eve", DigestFeedbackRequest(0, true)).status)
        }
    }
}
