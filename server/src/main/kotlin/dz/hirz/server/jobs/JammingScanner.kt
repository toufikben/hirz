package dz.hirz.server.jobs

import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.rules.Finding
import dz.hirz.server.rules.FindingRecorder
import dz.hirz.server.rules.VehicleRules
import dz.hirz.server.ts
import dz.hirz.server.vehicles.VehicleModes
import dz.hirz.shared.rules.RuleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/** JAMMING by silence (design §4.3): the engine was running and every source of the vehicle went quiet at once. */
class JammingScanner(
    private val db: DataSource, private val clock: Clock, private val cfg: RuleConfig,
    private val modes: VehicleModes, private val recorder: FindingRecorder,
) {
    private val log = LoggerFactory.getLogger(JammingScanner::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 30_000): Job = scope.launch {
        while (isActive) {
            runCatching { scanOnce() }.onFailure { log.error("jamming scan failed", it) }
            delay(everyMs)
        }
    }

    fun scanOnce() {
        val now = clock.instant()
        data class Row(val asset: UUID, val circle: UUID)
        val candidates = db.connection.use { c ->
            c.prepareStatement(
                """SELECT a.id, a.circle_id FROM vehicle_state v JOIN assets a ON a.id = v.asset_id
                   WHERE v.ignition IS TRUE AND a.lifecycle NOT IN ('paused', 'archived')""",
            ).executeQuery().toList { Row(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java)) }
        }
        for (r in candidates) {
            val hit = db.tx { c ->
                val last = c.prepareStatement(
                    "SELECT max(GREATEST(COALESCE(last_heartbeat_at, last_fix_at), COALESCE(last_fix_at, last_heartbeat_at))) FROM sources WHERE asset_id = ? AND revoked_at IS NULL",
                ).apply { setObject(1, r.asset) }.executeQuery().use { it.next(); it.getTimestamp(1)?.toInstant() }
                val flagged = c.prepareStatement("SELECT jamming_since IS NOT NULL FROM vehicle_rule_state WHERE asset_id = ?")
                    .apply { setObject(1, r.asset) }.executeQuery().use { it.next() && it.getBoolean(1) }
                val mode = modes.current(c, r.asset, now)
                VehicleRules.jamming(last, true, flagged, mode.mode, now, cfg)?.also {
                    c.prepareStatement(
                        """INSERT INTO vehicle_rule_state (asset_id, jamming_since) VALUES (?, ?)
                           ON CONFLICT (asset_id) DO UPDATE SET jamming_since = EXCLUDED.jamming_since""",
                    ).apply { setObject(1, r.asset); setTimestamp(2, now.ts()) }.executeUpdate()
                }
            } ?: continue
            recorder.record(Finding(r.asset, r.circle, hit.rule, hit.tier, listOf(hit.reason), now))
        }
    }
}
