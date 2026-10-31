package dz.hirz.server.trials

import dz.hirz.server.support.TestEnv
import dz.hirz.server.support.newAsset
import dz.hirz.server.support.newCircle
import dz.hirz.server.support.postJson
import dz.hirz.server.support.withApp
import dz.hirz.shared.api.CreateTrialRequest
import dz.hirz.shared.api.TrialDto
import dz.hirz.shared.api.TrialReportDto
import dz.hirz.shared.rules.FalseAlarmBudget
import dz.hirz.shared.rules.Verdict
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private suspend fun HttpClient.trial(): TrialDto =
    postJson("/v1/operator/trials", "test.ops", CreateTrialRequest("pilot", "2026-11-01", "2026-11-28")).body()

/**
 * [days] asset-days of a quiet, well-run trial over 4 assets: full coverage, every guided test rang. One row in ten
 * has a P0 guided test delivered in 6 s and claimed in 70 s, and one closed P1 that was reviewed.
 */
private fun seed(env: TestEnv, trialId: String, days: Int, falseP0: Int = 0, falseP1: Int = 0) = env.db.connection.use { c ->
    repeat(days) { i ->
        val p0 = i % 10 == 0
        c.createStatement().executeUpdate(
            """INSERT INTO trial_daily (trial_id, circle_pseudo, asset_pseudo, day, live_hours, fresh_hours, findings, false_p0, false_p1,
                 closed_p0p1, reviewed_p0p1, guided_tests, guided_missed, p0_guided, p0_delivery_ms, p0_ack_ms, digest_rated, digest_useful)
               VALUES ('$trialId', 'c${i % 2}', 'a${i % 4}', DATE '${LocalDate.parse("2026-11-01").plusDays((i / 4).toLong())}', 24, 23,
                 '{"UNUSUAL_STOP/P2": 1}'::jsonb, ${if (i < falseP0) 1 else 0}, ${if (i < falseP1) 1 else 0},
                 ${if (p0) 2 else 0}, ${if (p0) 2 else 0}, ${if (p0) 1 else 0}, 0, ${if (p0) 1 else 0},
                 ${if (p0) "'{6000}'" else "'{}'"}::bigint[], ${if (p0) "'{70000}'" else "'{}'"}::bigint[], 2, 2)""",
        )
    }
}

class TrialReportTest {
    @Test
    fun reportJudgesAgainstTheBudget() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val t = client.trial()
            seed(env, t.id, 120, falseP1 = 3)
            val r: TrialReportDto = client.get("/v1/operator/trials/${t.id}/report") { bearerAuth("test.ops") }.body()
            assertEquals(120, r.metrics.assetDays)
            assertTrue(r.passed, r.lines.filter { it.verdict != Verdict.PASS }.toString())
            assertEquals(120, r.findings["UNUSUAL_STOP/P2"])
            seed(env, t.id, 1, falseP0 = 1)
            val worse: TrialReportDto = client.get("/v1/operator/trials/${t.id}/report") { bearerAuth("test.ops") }.body()
            assertFalse(worse.passed)
            assertEquals(Verdict.FAIL, worse.lines.single { it.id == "false_p0" }.verdict, "one false P0 in 121 asset-days")
        }
    }

    @Test
    fun csvHasOneLinePerBudgetLineAndRule() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val t = client.trial()
            seed(env, t.id, 120)
            val csv = client.get("/v1/operator/trials/${t.id}/report?format=csv") { bearerAuth("test.ops") }.bodyAsText().trim().lines()
            assertEquals("kind,id,verdict,actual,limit", csv.first())
            assertEquals(FalseAlarmBudget.LINES.size, csv.count { it.startsWith("budget,") })
            assertTrue(csv.any { it.startsWith("rule,UNUSUAL_STOP/P2,") })
        }
    }

    @Test
    fun reportHasNoPersonalData() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val t = client.trial()
            val circle = client.newCircle("test.mom")
            val kid = client.newAsset("test.mom", circle.id, name = "Yanis")
            seed(env, t.id, 120)
            val text = client.get("/v1/operator/trials/${t.id}/report") { bearerAuth("test.ops") }.bodyAsText()
            listOf(circle.id, kid.id, "Yanis").forEach { assertFalse(it in text) }
        }
    }

    @Test
    fun notEnoughDataSaysSo() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val t = client.trial()
            seed(env, t.id, 20)
            val r: TrialReportDto = client.get("/v1/operator/trials/${t.id}/report") { bearerAuth("test.ops") }.body()
            assertEquals(Verdict.NOT_ENOUGH_DATA, r.lines.single { it.id == "false_p0" }.verdict)
            assertFalse(r.passed)
        }
    }

    @Test
    fun onlyOperatorsReadReports() {
        val env = TestEnv()
        withApp(env) { client, _ ->
            val t = client.trial()
            assertEquals(HttpStatusCode.Forbidden, client.get("/v1/operator/trials/${t.id}/report") { bearerAuth("test.mom") }.status)
        }
    }
}
