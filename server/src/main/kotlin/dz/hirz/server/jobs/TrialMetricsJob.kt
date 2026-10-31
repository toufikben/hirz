package dz.hirz.server.jobs

import dz.hirz.server.assets.ProfileService
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.ts
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
 * only: no names, ids, coordinates or reason text. Re-running a day replaces its rows.
 *
 * A guided test "rang" when an incident of its rule opened on its asset within [GUIDED_WINDOW_S]; an incident a
 * guided test caused is a test, never a false alarm, whatever its review says.
 */
class TrialMetricsJob(private val db: DataSource, private val clock: Clock, private val cfg: RuleConfig) {
    private val log = LoggerFactory.getLogger(TrialMetricsJob::class.java)

    /** Hourly; from 03:30 Algiers it (re)computes yesterday, which is idempotent. */
    fun start(scope: CoroutineScope, everyMs: Long = 3_600_000L): Job = scope.launch {
        while (isActive) {
            runCatching {
                val local = clock.instant().atZone(ALGIERS)
                if (local.toLocalTime() >= LocalTime.of(3, 30)) runFor(local.toLocalDate().minusDays(1))
            }.onFailure { log.error("trial metrics failed", it) }
            delay(everyMs)
        }
    }

    fun runFor(day: LocalDate) {
        data class Enrolled(val trial: UUID, val circle: UUID, val pseudonym: String)
        val enrolled = db.connection.use { c ->
            c.prepareStatement(
                """SELECT tc.trial_id, tc.circle_id, tc.pseudonym FROM trial_circles tc JOIN trials t ON t.id = tc.trial_id
                   WHERE tc.left_at IS NULL AND t.starts_on <= ? AND t.ends_on >= ?""",
            ).apply { setObject(1, day); setObject(2, day) }
                .executeQuery().toList { Enrolled(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getString(3)) }
        }
        val from = day.atStartOfDay(ALGIERS).toInstant()
        val to = day.plusDays(1).atStartOfDay(ALGIERS).toInstant()
        for (e in enrolled) runCatching {
            db.tx { c ->
                val assets = c.prepareStatement("SELECT id FROM assets WHERE circle_id = ? AND lifecycle <> 'archived'").apply { setObject(1, e.circle) }
                    .executeQuery().toList { it.getObject(1, UUID::class.java) }
                for (a in assets) store(c, e.trial, e.pseudonym, pseudo(e.pseudonym, a), a, e.circle, day, from, to)
            }
        }.onFailure { log.error("trial metrics ${e.pseudonym}", it) }
    }

    private fun pseudo(circlePseudo: String, asset: UUID): String =
        MessageDigest.getInstance("SHA-256").digest("$circlePseudo:$asset".toByteArray()).take(8).joinToString("") { "%02x".format(it) }

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

        data class Inc(val tier: String, val closed: Boolean, val reviewed: Boolean, val falseAlarm: Boolean, val deliveredMs: Long?, val ackMs: Long?)
        val incidents = c.prepareStatement(
            """SELECT i.tier, i.state = 'closed',
                 EXISTS (SELECT 1 FROM incident_reviews r WHERE r.incident_id = i.id),
                 EXISTS (SELECT 1 FROM incident_reviews r WHERE r.incident_id = i.id AND r.false_alarm)
                   AND NOT EXISTS (SELECT 1 FROM incident_reviews r WHERE r.incident_id = i.id AND r.outcome = 'test')
                   AND NOT EXISTS (SELECT 1 FROM guided_tests g WHERE g.asset_id = i.asset_id AND g.rule = i.rule
                                   AND i.opened_at >= g.started_at AND i.opened_at <= g.started_at + make_interval(secs => ?)),
                 (EXTRACT(EPOCH FROM (SELECT min(d.delivered_at) FROM deliveries d WHERE d.incident_id = i.id) - i.opened_at) * 1000)::bigint,
                 (EXTRACT(EPOCH FROM i.claimed_at - i.opened_at) * 1000)::bigint
               FROM incidents i WHERE i.asset_id = ? AND i.tier IN ('P0', 'P1') AND i.opened_at >= ? AND i.opened_at < ?""",
        ).apply { setInt(1, GUIDED_WINDOW_S); setObject(2, asset); setTimestamp(3, from.ts()); setTimestamp(4, to.ts()) }.executeQuery().toList {
            Inc(it.getString(1), it.getBoolean(2), it.getBoolean(3), it.getBoolean(4),
                it.getLong(5).takeUnless { _ -> it.wasNull() }, it.getLong(6).takeUnless { _ -> it.wasNull() })
        }
        val closed = incidents.filter { it.closed }
        val p0 = incidents.filter { it.tier == "P0" }

        data class Guided(val rule: String, val at: Instant, val rang: Boolean)
        val guided = c.prepareStatement(
            """SELECT g.rule, g.started_at, EXISTS (SELECT 1 FROM incidents i WHERE i.asset_id = g.asset_id AND i.rule = g.rule
                 AND i.opened_at >= g.started_at AND i.opened_at <= g.started_at + make_interval(secs => ?))
               FROM guided_tests g WHERE g.asset_id = ? AND g.started_at >= ? AND g.started_at < ?""",
        ).apply { setInt(1, GUIDED_WINDOW_S); setObject(2, asset); setTimestamp(3, from.ts()); setTimestamp(4, to.ts()) }
            .executeQuery().toList { Guided(it.getString(1), it.getTimestamp(2).toInstant(), it.getBoolean(3)) }
        val p0Guided = guided.count { g ->
            val rule = RuleId.entries.firstOrNull { it.name == g.rule } ?: return@count false
            ProfileService.loadAt(c, asset, g.at)?.tierOf(rule) == Tier.P0
        }

        val (rated, useful) = c.prepareStatement(
            """SELECT count(*), count(*) FILTER (WHERE f.useful) FROM digests d
               CROSS JOIN LATERAL jsonb_array_elements(d.items) WITH ORDINALITY AS e(item, n)
               JOIN digest_feedback f ON f.digest_id = d.id AND f.item_index = e.n - 1
               WHERE d.circle_id = ? AND d.day = ? AND e.item->>'assetId' = ?""",
        ).apply { setObject(1, circle); setObject(2, day); setString(3, asset.toString()) }.executeQuery().use { it.next(); it.getInt(1) to it.getInt(2) }

        c.prepareStatement(
            """INSERT INTO trial_daily (trial_id, circle_pseudo, asset_pseudo, day, live_hours, fresh_hours, findings, false_p0, false_p1,
                 closed_p0p1, reviewed_p0p1, guided_tests, guided_missed, p0_guided, p0_delivery_ms, p0_ack_ms, digest_rated, digest_useful)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (trial_id, asset_pseudo, day) DO UPDATE SET live_hours = EXCLUDED.live_hours, fresh_hours = EXCLUDED.fresh_hours,
                 findings = EXCLUDED.findings, false_p0 = EXCLUDED.false_p0, false_p1 = EXCLUDED.false_p1, closed_p0p1 = EXCLUDED.closed_p0p1,
                 reviewed_p0p1 = EXCLUDED.reviewed_p0p1, guided_tests = EXCLUDED.guided_tests, guided_missed = EXCLUDED.guided_missed,
                 p0_guided = EXCLUDED.p0_guided, p0_delivery_ms = EXCLUDED.p0_delivery_ms, p0_ack_ms = EXCLUDED.p0_ack_ms,
                 digest_rated = EXCLUDED.digest_rated, digest_useful = EXCLUDED.digest_useful""",
        ).apply {
            setObject(1, trial); setString(2, circlePseudo); setString(3, assetPseudo); setObject(4, day)
            setInt(5, hours(good = false)); setInt(6, hours(good = true)); setString(7, findingsJson)
            setInt(8, closed.count { it.tier == "P0" && it.falseAlarm }); setInt(9, closed.count { it.tier == "P1" && it.falseAlarm })
            setInt(10, closed.size); setInt(11, closed.count { it.reviewed })
            setInt(12, guided.size); setInt(13, guided.count { !it.rang }); setInt(14, p0Guided)
            setArray(15, c.createArrayOf("bigint", p0.mapNotNull { it.deliveredMs }.toTypedArray()))
            setArray(16, c.createArrayOf("bigint", p0.mapNotNull { it.ackMs }.toTypedArray()))
            setInt(17, rated); setInt(18, useful)
        }.executeUpdate()
    }

    companion object {
        private val ALGIERS = ZoneId.of("Africa/Algiers")
        /** How long after a guided test its alarm may open and still count as having rung. */
        const val GUIDED_WINDOW_S = 15 * 60
    }
}
