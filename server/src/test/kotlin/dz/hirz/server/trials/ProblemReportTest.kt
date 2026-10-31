package dz.hirz.server.trials

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CONSENT_VERSION
import dz.hirz.shared.api.CreateTrialRequest
import dz.hirz.shared.api.JoinTrialRequest
import dz.hirz.shared.api.ProblemReportRequest
import dz.hirz.shared.api.TrialDto
import dz.hirz.shared.api.TrialReportDto
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun stored(env: TestEnv): List<String> = env.db.connection.use { c ->
    c.createStatement().executeQuery("SELECT text FROM problem_reports").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
}

class ProblemReportTest {
    @Test
    fun memberReportsAProblem() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            assertEquals(HttpStatusCode.NoContent,
                client.postJson("/v1/feedback", "test.mom", ProblemReportRequest(circle.id, "The alarm rang twice", "1.4.0", "Pixel 7")).status)
            assertEquals(listOf("The alarm rang twice"), stored(env))
            assertEquals(HttpStatusCode.UnprocessableEntity, client.postJson("/v1/feedback", "test.mom", ProblemReportRequest(circle.id, "  ", "1.4.0", "Pixel 7")).status)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/v1/feedback", "test.eve", ProblemReportRequest(circle.id, "hi", "1.4.0", "x")).status)
        }
    }

    @Test
    fun textIsCappedAndNoLocationField() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val circle = client.newCircle("test.mom")
            client.postJson("/v1/feedback", "test.mom", ProblemReportRequest(circle.id, "x".repeat(5000), "1.4.0", "Pixel 7"))
            assertEquals(2000, stored(env).single().length)
            val columns = env.db.connection.use { c ->
                c.createStatement().executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = 'problem_reports'")
                    .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            assertTrue(columns.none { "lat" in it || "lon" in it || "geog" in it || "position" in it }, "$columns")
        }
    }

    @Test
    fun operatorSeesPseudonymNotCircle() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val trial: TrialDto = client.postJson("/v1/operator/trials", "test.ops", CreateTrialRequest("pilot", "2026-11-01", "2026-11-28")).body()
            val circle = client.newCircle("test.mom")
            client.postJson("/v1/circles/${circle.id}/trial", "test.mom", JoinTrialRequest(trial.id, CONSENT_VERSION))
            client.postJson("/v1/feedback", "test.mom", ProblemReportRequest(circle.id, "Push came late", "1.4.0", "Galaxy A14"))
            val r: TrialReportDto = client.get("/v1/operator/trials/${trial.id}/report") { bearerAuth("test.ops") }.body()
            val p = r.problems.single()
            assertEquals("Push came late", p.text)
            assertFalse(circle.id in p.circlePseudo)
        }
    }
}
