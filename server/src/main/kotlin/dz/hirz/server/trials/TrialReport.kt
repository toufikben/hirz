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
        // Latencies: every P0, with those never delivered or never claimed as infinitely slow.
        val delivered = mutableListOf<Long>(); val acked = mutableListOf<Long>()
        c.prepareStatement("SELECT p0_delivery_ms, p0_ack_ms, p0_undelivered, p0_unclaimed FROM trial_daily WHERE trial_id = ?")
            .apply { setObject(1, trialId) }.executeQuery().use { rs ->
                while (rs.next()) {
                    (rs.getArray(1).array as Array<*>).forEach { delivered += (it as Number).toLong() }
                    (rs.getArray(2).array as Array<*>).forEach { acked += (it as Number).toLong() }
                    repeat(rs.getInt(3)) { delivered += Long.MAX_VALUE }
                    repeat(rs.getInt(4)) { acked += Long.MAX_VALUE }
                }
            }
        val metrics = c.prepareStatement(
            """SELECT count(*) FILTER (WHERE live_hours >= ?), COALESCE(sum(false_p0), 0), COALESCE(sum(false_p1), 0),
                 COALESCE(sum(guided_tests), 0), COALESCE(sum(guided_missed), 0), COALESCE(sum(p0_guided), 0),
                 COALESCE(sum(fresh_hours), 0), count(*),
                 COALESCE(sum(digest_rated), 0), COALESCE(sum(digest_useful), 0), COALESCE(sum(closed_p0p1), 0), COALESCE(sum(reviewed_p0p1), 0),
                 count(DISTINCT circle_pseudo), COALESCE(sum(p0_alarms), 0), COALESCE(sum(unsure_reviews), 0), COALESCE(sum(unmatched_tests), 0)
               FROM trial_daily WHERE trial_id = ?""",
        ).apply { setInt(1, LIVE_HOURS_FOR_A_DAY); setObject(2, trialId) }.executeQuery().use { rs ->
            rs.next()
            val rows = rs.getInt(8)
            TrialMetrics(
                assetDays = rs.getInt(1), falseP0 = rs.getInt(2), falseP1 = rs.getInt(3),
                guidedTests = rs.getInt(4), guidedMissed = rs.getInt(5), p0GuidedTests = rs.getInt(6),
                p0DeliveryP95Ms = percentile(delivered, 0.95), p0AckMedianMs = percentile(acked, 0.5),
                // Every enrolled asset-day counts, silent ones included: a dead device is not hidden.
                coverage = if (rows > 0) rs.getLong(7).toDouble() / (24.0 * rows) else null,
                digestRated = rs.getInt(9), digestUseful = rs.getInt(10), closedP0P1 = rs.getInt(11), reviewedP0P1 = rs.getInt(12),
                p0Alarms = rs.getInt(14), unsureReviews = rs.getInt(15), unmatchedTests = rs.getInt(16),
            ) to rs.getInt(13)
        }
        val findings = c.prepareStatement(
            "SELECT k, sum(v::int) FROM trial_daily, jsonb_each_text(findings) AS f(k, v) WHERE trial_id = ? GROUP BY k ORDER BY k",
        ).apply { setObject(1, trialId) }.executeQuery().toList { it.getString(1) to it.getInt(2) }.toMap()
        // Reports from the trial's circles during the trial, under their pseudonym.
        val problems = c.prepareStatement(
            """SELECT tc.pseudonym, p.text, p.app_version, p.device, p.at FROM problem_reports p
               JOIN trial_circles tc ON tc.circle_id = p.circle_id AND tc.trial_id = ? AND tc.left_at IS NULL AND p.at >= tc.consent_at
               JOIN trials t ON t.id = tc.trial_id AND p.at < (t.ends_on + 1)::timestamp AT TIME ZONE 'Africa/Algiers'
               ORDER BY p.at DESC LIMIT 500""",
        ).apply { setObject(1, trialId) }.executeQuery()
            .toList { dz.hirz.shared.api.ProblemReportDto(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getTimestamp(5).time) }
        val lines = BudgetGate.judge(metrics.first)
        TrialReportDto(trial, metrics.second, metrics.first, lines, BudgetGate.passed(lines), findings, problems)
    }

    /** Nearest-rank percentile; null for no values. */
    private fun percentile(values: List<Long>, p: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[(kotlin.math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
    }

    fun csv(r: TrialReportDto): String = buildString {
        fun cell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
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
