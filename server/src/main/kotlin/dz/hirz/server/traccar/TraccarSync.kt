package dz.hirz.server.traccar

import dz.hirz.server.auth.SourcePrincipal
import dz.hirz.server.db.toList
import dz.hirz.server.ingest.IngestService
import dz.hirz.shared.api.IngestBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private val DEFAULT_LOOKBACK: Duration = Duration.ofHours(1)
private val MAX_LOOKBACK: Duration = Duration.ofHours(6)

/**
 * The backup path (design §2 rule 2): positions the forwarder never delivered are pulled from Traccar's REST API.
 * Client ids `tc-<id>` make anything already forwarded a duplicate, so the two paths never double-count.
 */
class TraccarSync(private val db: DataSource, private val clock: Clock, private val admin: TraccarAdmin, private val ingest: IngestService) {
    private val log = LoggerFactory.getLogger(TraccarSync::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 300_000): Job = scope.launch {
        while (isActive) {
            runCatching { syncOnce() }.onFailure { log.warn("Traccar backup sync failed", it) }
            delay(everyMs)
        }
    }

    /** Returns how many positions were new. */
    fun syncOnce(): Int {
        if (admin === TraccarAdmin.NONE) return 0
        val now = clock.instant()
        data class Row(val principal: SourcePrincipal, val device: Long, val lastFix: Instant?, val created: Instant)
        val rows = db.connection.use { c ->
            c.createStatement().executeQuery(
                """SELECT s.id, s.asset_id, a.circle_id, s.traccar_device_id, s.last_fix_at, s.created_at FROM sources s JOIN assets a ON a.id = s.asset_id
                   WHERE s.kind = 'traccar' AND s.revoked_at IS NULL AND s.traccar_device_id IS NOT NULL""",
            ).toList {
                Row(SourcePrincipal(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getObject(3, UUID::class.java)),
                    it.getLong(4), it.getTimestamp(5)?.toInstant(), it.getTimestamp(6).toInstant())
            }
        }
        var added = 0
        val floor = now.minus(MAX_LOOKBACK)
        for (r in rows) {
            val since = r.lastFix ?: now.minus(DEFAULT_LOOKBACK)
            // Never before the source existed: a reused Traccar device may hold a previous holder's positions (review I3).
            val from = listOf(if (since.isBefore(floor)) floor else since.minusSeconds(60), r.created).max()
            admin.positions(r.device, from, now).chunked(100).forEach { batch ->
                added += ingest.ingest(r.principal, IngestBatch(batch.map { TraccarMapper.toSignal(it) })).accepted
            }
        }
        return added
    }
}
