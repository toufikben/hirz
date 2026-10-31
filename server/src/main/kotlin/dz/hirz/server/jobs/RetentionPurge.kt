package dz.hirz.server.jobs

import dz.hirz.server.ts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import javax.sql.DataSource

/**
 * Daily purge (design §8.3): positions older than the circle's retention (30 days by default) are deleted,
 * except the evidence window (1 h before opening → closing) of a P0/P1 incident that is open or closed in the last
 * 90 days. Routine P2 findings (a phone off at night) never suspend retention (P1 review #7).
 * The location access log is kept 180 days.
 */
class RetentionPurge(private val db: DataSource, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(RetentionPurge::class.java)

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            runCatching { runOnce() }.onFailure { log.error("retention purge failed", it) }
            delay(24 * 3_600_000L)
        }
    }

    fun runOnce(): Int = db.connection.use { c ->
        val now = clock.instant().ts()
        val positions = c.prepareStatement(
            """DELETE FROM positions p USING assets a, circles ci
               WHERE p.asset_id = a.id AND a.circle_id = ci.id
                 AND p.server_time < ?::timestamptz - make_interval(days => ci.retention_days)
                 AND NOT EXISTS (
                   SELECT 1 FROM incidents i WHERE i.asset_id = p.asset_id AND i.tier IN ('P0', 'P1')
                     AND (i.state = 'open' OR i.closed_at > ?::timestamptz - interval '90 days')
                     AND p.server_time BETWEEN i.opened_at - interval '1 hour' AND COALESCE(i.closed_at, 'infinity'::timestamptz))""",
        ).apply { setTimestamp(1, now); setTimestamp(2, now) }.executeUpdate()
        c.prepareStatement("DELETE FROM location_views WHERE at < ?::timestamptz - interval '180 days'")
            .apply { setTimestamp(1, now) }.executeUpdate()
        // SMS hold phone numbers and replies: 30 days, like positions (review #5).
        c.prepareStatement("DELETE FROM inbound_sms WHERE received_at < ?::timestamptz - interval '30 days'").apply { setTimestamp(1, now) }.executeUpdate()
        // Who relayed which tag is kept a week, for presence, and no longer.
        c.prepareStatement("DELETE FROM ble_sightings WHERE seen_at < ?::timestamptz - interval '7 days'").apply { setTimestamp(1, now) }.executeUpdate()
        c.prepareStatement("DELETE FROM outbound_sms WHERE created_at < ?::timestamptz - interval '30 days'").apply { setTimestamp(1, now) }.executeUpdate()
        log.info("retention purge removed {} positions", positions)
        positions
    }
}
