package dz.hirz.server.jobs

import dz.hirz.server.assets.ProfileService
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.ts
import dz.hirz.shared.api.CONSENT_VERSION
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import dz.hirz.shared.rules.RuleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * Nightly trial metrics (P8): one row per enrolled asset and day, keyed by pseudonyms. It holds counts and latencies
 * only: no names, ids, coordinates or reason text. Every day of a trial is recomputed each night until
 * [GRACE_DAYS] after it ends, so a review or a digest vote given days later still counts. Re-running a day replaces
 * its rows.
 *
 * A guided test "rang" when an alerting incident of its rule (P0/P1, not a quiet verify-first one, a push sent) opened
 * on its asset within [GUIDED_WINDOW_S]. That incident is a test, never a false alarm. A review that says "test"
 * without a guided test, or "unsure", is not a decided outcome: it does not count as reviewed.
 */
class TrialMetricsJob(private val db: DataSource, private val clock: Clock, private val cfg: RuleConfig) {
    private val log = LoggerFactory.getLogger(TrialMetricsJob::class.java)

    /** Hourly; from 03:30 Algiers it recomputes every day of the live trials (idempotent). */
    fun start(scope: CoroutineScope, everyMs: Long = 3_600_000L): Job = scope.launch {
        while (isActive) {
            runCatching { if (clock.instant().atZone(ALGIERS).toLocalTime() >= LocalTime.of(3, 30)) runAll() }
                .onFailure { log.error("trial metrics failed", it) }
            delay(everyMs)
        }
    }

    /** Recomputes each day from a trial's start to yesterday (at most its end), for trials not past their grace. */
    fun runAll() {
        val today = clock.instant().atZone(ALGIERS).toLocalDate()
        val yesterday = today.minusDays(1)
        val days = db.connection.use { c ->
            c.prepareStatement(
                """SELECT DISTINCT d::date FROM trials t, generate_series(t.starts_on, LEAST(t.ends_on, ?), interval '1 day') d
                   WHERE t.ends_on >= ? ORDER BY 1""",
            ).apply { setObject(1, yesterday); setObject(2, today.minusDays(GRACE_DAYS)) }
                .executeQuery().toList { it.getDate(1).toLocalDate() }
        }
        days.forEach(::runFor)
        // Trial data outlives its trial by [RETENTION_DAYS] only.
        db.tx { c ->
            c.prepareStatement("DELETE FROM trial_daily d USING trials t WHERE t.id = d.trial_id AND t.ends_on < ?")
                .apply { setObject(1, today.minusDays(RETENTION_DAYS)) }.executeUpdate()
        }
    }

    fun runFor(day: LocalDate) {
        val from = day.atStartOfDay(ALGIERS).toInstant()
        val to = day.plusDays(1).atStartOfDay(ALGIERS).toInstant()
        data class Enrolled(val trial: UUID, val circle: UUID)
        val enrolled = db.connection.use { c ->
            c.prepareStatement(
                """SELECT tc.trial_id, tc.circle_id FROM trial_circles tc JOIN trials t ON t.id = tc.trial_id
                   WHERE tc.left_at IS NULL AND tc.consent_at < ? AND tc.consent_version = ? AND t.starts_on <= ? AND t.ends_on >= ?""",
            ).apply { setTimestamp(1, to.ts()); setInt(2, CONSENT_VERSION); setObject(3, day); setObject(4, day) }
                .executeQuery().toList { Enrolled(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java)) }
        }
        for (e in enrolled) runCatching {
            db.tx { c ->
                // Holds the enrolment against a concurrent leave, and skips a circle that left meanwhile.
                val pseudonym = c.prepareStatement(
                    "SELECT pseudonym FROM trial_circles WHERE trial_id = ? AND circle_id = ? AND left_at IS NULL FOR UPDATE",
                ).apply { setObject(1, e.trial); setObject(2, e.circle) }.executeQuery().use { if (it.next()) it.getString(1) else null } ?: return@tx
                // Assets that had a source that day, archived since or not.
                val assets = c.prepareStatement(
                    "SELECT DISTINCT a.id FROM assets a JOIN sources s ON s.asset_id = a.id WHERE a.circle_id = ? AND s.created_at < ? AND (s.revoked_at IS NULL OR s.revoked_at >= ?)",
                ).apply { setObject(1, e.circle); setTimestamp(2, to.ts()); setTimestamp(3, from.ts()) }.executeQuery().toList { it.getObject(1, UUID::class.java) }
                for (a in assets) store(c, e.trial, pseudonym, pseudo(pseudonym, a), a, e.circle, day, from, to)
            }
        }.onFailure { log.error("trial metrics ${e.trial}/$day", it) }
    }

    private fun pseudo(circlePseudo: String, asset: UUID): String =
        MessageDigest.getInstance("SHA-256").digest("$circlePseudo:$asset".toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /** SQL: an alerting incident of guided test `g` opened within the window (P0/P1, not quiet, a push sent). */
    private val rangSql = """EXISTS (SELECT 1 FROM incidents i2 WHERE i2.asset_id = g.asset_id AND i2.rule = g.rule
        AND i2.tier IN ('P0', 'P1') AND NOT i2.verify_first
        AND i2.opened_at >= g.started_at AND i2.opened_at <= g.started_at + make_interval(secs => $GUIDED_WINDOW_S)
        AND EXISTS (SELECT 1 FROM deliveries d WHERE d.incident_id = i2.id AND d.sent_at IS NOT NULL))"""

    private fun store(c: Connection, trial: UUID, circlePseudo: String, assetPseudo: String, asset: UUID, circle: UUID, day: LocalDate, from: Instant, to: Instant) {
        fun hours(good: Boolean): Int = c.prepareStatement(
            """SELECT count(DISTINCT date_trunc('hour', COALESCE(fix_time, server_time) AT TIME ZONE 'Africa/Algiers')) FROM positions
               WHERE asset_id = ? AND COALESCE(fix_time, server_time) >= ? AND COALESCE(fix_time, server_time) < ?
               ${if (good) "AND geog IS NOT NULL AND quality = 'ok' AND COALESCE(accuracy_m, 9999) <= ?" else ""}""",
        ).apply { setObject(1, asset); setTimestamp(2, from.ts()); setTimestamp(3, to.ts()); if (good) setDouble(4, cfg.boundaryAccuracyFloorM) }
            .executeQuery().use { it.next(); it.getInt(1) }

        val findings = c.prepareStatement("SELECT rule || '/' || tier, count(*) FROM findings WHERE asset_id = ? AND at >= ? AND at < ? GROUP BY 1")
            .apply { setObject(1, asset); setTimestamp(2, from.ts()); setTimestamp(3, to.ts()) }
            .executeQuery().toList { it.getString(1) to it.getInt(2) }
        val findingsJson = JsonObject(findings.associate { (k, v) -> k to JsonPrimitive(v) }).toString()

        // Per incident: was it caused by a guided test, is it decided (reviewed with a usable outcome), is it false.
        data class Inc(val tier: String, val closed: Boolean, val guided: Boolean, val decided: Boolean, val falseAlarm: Boolean,
                       val unsure: Boolean, val unmatchedTest: Boolean, val deliveredMs: Long?, val ackMs: Long?)
        val incidents = c.prepareStatement(
            """SELECT i.tier, i.state = 'closed',
                 EXISTS (SELECT 1 FROM guided_tests g WHERE g.asset_id = i.asset_id AND g.rule = i.rule
                         AND i.opened_at >= g.started_at AND i.opened_at <= g.started_at + make_interval(secs => ?)),
                 EXISTS (SELECT 1 FROM incident_reviews r WHERE r.incident_id = i.id AND (r.outcome IS NULL OR r.outcome IN ('real', 'false_device', 'false_rule'))),
                 EXISTS (SELECT 1 FROM incident_reviews r WHERE r.incident_id = i.id AND r.false_alarm),
                 EXISTS (SELECT 1 FROM incident_reviews r WHERE r.incident_id = i.id AND r.outcome = 'unsure'),
                 EXISTS (SELECT 1 FROM incident_reviews r WHERE r.incident_id = i.id AND r.outcome = 'test'),
                 (EXTRACT(EPOCH FROM (SELECT min(d.delivered_at) FROM deliveries d WHERE d.incident_id = i.id) - i.opened_at) * 1000)::bigint,
                 (EXTRACT(EPOCH FROM i.claimed_at - i.opened_at) * 1000)::bigint
               FROM incidents i WHERE i.asset_id = ? AND i.tier IN ('P0', 'P1') AND i.opened_at >= ? AND i.opened_at < ?""",
        ).apply { setInt(1, GUIDED_WINDOW_S); setObject(2, asset); setTimestamp(3, from.ts()); setTimestamp(4, to.ts()) }.executeQuery().toList {
            val guided = it.getBoolean(3)
            val saysTest = it.getBoolean(7)
            Inc(
                tier = it.getString(1), closed = it.getBoolean(2), guided = guided,
                // "It was a test" is accepted only where a guided test was announced.
                decided = it.getBoolean(4) || (saysTest && guided),
                falseAlarm = !guided && it.getBoolean(5),
                unsure = it.getBoolean(6), unmatchedTest = saysTest && !guided,
                deliveredMs = it.getLong(8).takeUnless { _ -> it.wasNull() }, ackMs = it.getLong(9).takeUnless { _ -> it.wasNull() },
            )
        }
        val closed = incidents.filter { it.closed }
        val p0 = incidents.filter { it.tier == "P0" }

        data class Guided(val rule: String, val at: Instant, val rang: Boolean)
        val guided = c.prepareStatement(
            "SELECT g.rule, g.started_at, $rangSql FROM guided_tests g WHERE g.asset_id = ? AND g.started_at >= ? AND g.started_at < ?",
        ).apply { setObject(1, asset); setTimestamp(2, from.ts()); setTimestamp(3, to.ts()) }
            .executeQuery().toList { Guided(it.getString(1), it.getTimestamp(2).toInstant(), it.getBoolean(3)) }
        val p0Guided = guided.count { g ->
            val rule = RuleId.entries.firstOrNull { it.name == g.rule } ?: return@count false
            // SOS and duress are P0 for everyone and are not in the profile.
            rule == RuleId.SOS || rule == RuleId.DURESS || ProfileService.loadAt(c, asset, g.at)?.tierOf(rule) == Tier.P0
        }

        val (rated, useful) = c.prepareStatement(
            """SELECT count(*), count(*) FILTER (WHERE f.useful) FROM digests d
               CROSS JOIN LATERAL jsonb_array_elements(d.items) WITH ORDINALITY AS e(item, n)
               JOIN digest_feedback f ON f.digest_id = d.id AND f.item_index = e.n - 1
               WHERE d.circle_id = ? AND d.day = ? AND e.item->>'assetId' = ?""",
        ).apply { setObject(1, circle); setObject(2, day); setString(3, asset.toString()) }.executeQuery().use { it.next(); it.getInt(1) to it.getInt(2) }

        c.prepareStatement(
            """INSERT INTO trial_daily (trial_id, circle_pseudo, asset_pseudo, day, live_hours, fresh_hours, findings, false_p0, false_p1,
                 closed_p0p1, reviewed_p0p1, guided_tests, guided_missed, p0_guided, p0_delivery_ms, p0_ack_ms, digest_rated, digest_useful,
                 p0_alarms, p0_undelivered, p0_unclaimed, unsure_reviews, unmatched_tests)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (trial_id, asset_pseudo, day) DO UPDATE SET live_hours = EXCLUDED.live_hours, fresh_hours = EXCLUDED.fresh_hours,
                 findings = EXCLUDED.findings, false_p0 = EXCLUDED.false_p0, false_p1 = EXCLUDED.false_p1, closed_p0p1 = EXCLUDED.closed_p0p1,
                 reviewed_p0p1 = EXCLUDED.reviewed_p0p1, guided_tests = EXCLUDED.guided_tests, guided_missed = EXCLUDED.guided_missed,
                 p0_guided = EXCLUDED.p0_guided, p0_delivery_ms = EXCLUDED.p0_delivery_ms, p0_ack_ms = EXCLUDED.p0_ack_ms,
                 digest_rated = EXCLUDED.digest_rated, digest_useful = EXCLUDED.digest_useful, p0_alarms = EXCLUDED.p0_alarms,
                 p0_undelivered = EXCLUDED.p0_undelivered, p0_unclaimed = EXCLUDED.p0_unclaimed, unsure_reviews = EXCLUDED.unsure_reviews,
                 unmatched_tests = EXCLUDED.unmatched_tests""",
        ).apply {
            setObject(1, trial); setString(2, circlePseudo); setString(3, assetPseudo); setObject(4, day)
            setInt(5, hours(good = false)); setInt(6, hours(good = true)); setString(7, findingsJson)
            setInt(8, closed.count { it.tier == "P0" && it.falseAlarm }); setInt(9, closed.count { it.tier == "P1" && it.falseAlarm })
            setInt(10, closed.size); setInt(11, closed.count { it.decided })
            setInt(12, guided.size); setInt(13, guided.count { !it.rang }); setInt(14, p0Guided)
            setArray(15, c.createArrayOf("bigint", p0.mapNotNull { it.deliveredMs }.toTypedArray()))
            setArray(16, c.createArrayOf("bigint", p0.mapNotNull { it.ackMs }.toTypedArray()))
            setInt(17, rated); setInt(18, useful)
            setInt(19, p0.size); setInt(20, p0.count { it.deliveredMs == null }); setInt(21, p0.count { it.ackMs == null })
            setInt(22, closed.count { it.unsure }); setInt(23, closed.count { it.unmatchedTest })
        }.executeUpdate()
    }

    companion object {
        private val ALGIERS = ZoneId.of("Africa/Algiers")
        /** How long after a guided test its alarm may open and still count as having rung. */
        const val GUIDED_WINDOW_S = 15 * 60
        /** Days after a trial ends during which late reviews and votes are still counted. */
        const val GRACE_DAYS = 14L
        /** Days after a trial ends before its aggregates are deleted. */
        const val RETENTION_DAYS = 180L
    }
}
