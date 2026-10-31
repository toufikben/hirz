package dz.hirz.server.trials

import dz.hirz.server.circles.uuidOrNotFound
import dz.hirz.server.db.toList
import dz.hirz.server.member
import dz.hirz.server.notFound
import dz.hirz.shared.api.TrialDto
import dz.hirz.shared.api.TrialReportDto
import dz.hirz.shared.rules.BudgetGate
import dz.hirz.shared.rules.TrialMetrics
import io.ktor.http.ContentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.util.UUID
import javax.sql.DataSource

/** An asset-day counts toward the budget only with a live source for this many hours of it. */
const val LIVE_HOURS_FOR_A_DAY = 12

/**
 * The operator's trial report (P8): `trial_daily` aggregated over the whole trial and judged by [BudgetGate]. It
 * holds counts, rates and latencies only; operators never see circles, assets or positions.
 */
class TrialReport(private val db: DataSource, private val trials: TrialService) {
    fun build(uid: String, trialId: UUID): TrialReportDto = db.connection.use { c ->
        trials.requireOperator(uid)
        val trial = c.prepareStatement("SELECT * FROM trials WHERE id = ?").apply { setObject(1, trialId) }.executeQuery().use {
            if (!it.next()) notFound()
            TrialDto(it.getString("id"), it.getString("name"), it.getDate("starts_on").toString(), it.getDate("ends_on").toString())
        }
        val metrics = c.prepareStatement(
            """SELECT count(*) FILTER (WHERE live_hours >= ?), COALESCE(sum(false_p0), 0), COALESCE(sum(false_p1), 0),
                 COALESCE(sum(guided_tests), 0), COALESCE(sum(guided_missed), 0), COALESCE(sum(p0_guided), 0),
                 (SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY v) FROM trial_daily d2, unnest(d2.p0_delivery_ms) v WHERE d2.trial_id = ?),
                 (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY v) FROM trial_daily d3, unnest(d3.p0_ack_ms) v WHERE d3.trial_id = ?),
                 COALESCE(sum(fresh_hours) FILTER (WHERE live_hours >= ?), 0),
                 COALESCE(sum(digest_rated), 0), COALESCE(sum(digest_useful), 0), COALESCE(sum(closed_p0p1), 0), COALESCE(sum(reviewed_p0p1), 0),
                 count(DISTINCT circle_pseudo)
               FROM trial_daily WHERE trial_id = ?""",
        ).apply {
            setInt(1, LIVE_HOURS_FOR_A_DAY); setObject(2, trialId); setObject(3, trialId); setInt(4, LIVE_HOURS_FOR_A_DAY); setObject(5, trialId)
        }.executeQuery().use { rs ->
            rs.next()
            val days = rs.getInt(1)
            val p95 = rs.getDouble(7).takeUnless { rs.wasNull() }?.toLong()
            val median = rs.getDouble(8).takeUnless { rs.wasNull() }?.toLong()
            TrialMetrics(
                assetDays = days, falseP0 = rs.getInt(2), falseP1 = rs.getInt(3),
                guidedTests = rs.getInt(4), guidedMissed = rs.getInt(5), p0GuidedTests = rs.getInt(6),
                p0DeliveryP95Ms = p95, p0AckMedianMs = median,
                coverage = if (days > 0) rs.getLong(9).toDouble() / (24.0 * days) else null,
                digestRated = rs.getInt(10), digestUseful = rs.getInt(11), closedP0P1 = rs.getInt(12), reviewedP0P1 = rs.getInt(13),
            ) to rs.getInt(14)
        }
        val findings = c.prepareStatement(
            "SELECT k, sum(v::int) FROM trial_daily, jsonb_each_text(findings) AS f(k, v) WHERE trial_id = ? GROUP BY k ORDER BY k",
        ).apply { setObject(1, trialId) }.executeQuery().toList { it.getString(1) to it.getInt(2) }.toMap()
        val lines = BudgetGate.judge(metrics.first)
        TrialReportDto(trial, metrics.second, metrics.first, lines, BudgetGate.passed(lines), findings)
    }

    fun csv(r: TrialReportDto): String = buildString {
        fun cell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        appendLine("kind,id,verdict,actual,limit")
        r.lines.forEach { appendLine(listOf("budget", it.id, it.verdict.name.lowercase(), it.actual, it.limit).joinToString(",") { s -> cell(s) }) }
        r.findings.forEach { (k, v) -> appendLine(listOf("rule", k, "", v.toString(), "").joinToString(",") { s -> cell(s) }) }
    }
}

fun Route.trialReportRoutes(report: TrialReport) {
    get("/v1/operator/trials/{id}/report") {
        val r = report.build(call.member().uid, uuidOrNotFound(call.parameters["id"]))
        if (call.request.queryParameters["format"] == "csv") call.respondText(report.csv(r), ContentType.Text.CSV) else call.respond(r)
    }
}
