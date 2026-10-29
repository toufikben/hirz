package dz.hirz.server.rules

import dz.hirz.server.db.toList
import dz.hirz.server.ts
import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/** A check-in nobody answered within this long is a finding. */
const val CHECKIN_REPLY_MS = 5 * 60_000L

/**
 * NO_RESPONSE_CHECKIN (design §6.2): a check-in unanswered for 5 minutes is a P1 finding; with an open P0/P1
 * incident on the asset the reason is added to that incident instead. Each check-in is raised at most once.
 */
class CheckInScanner(private val db: DataSource, private val clock: Clock, private val recorder: FindingRecorder) {
    private val log = LoggerFactory.getLogger(CheckInScanner::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 30_000): Job = scope.launch {
        while (isActive) {
            runCatching { scanOnce() }.onFailure { log.error("check-in scan failed", it) }
            delay(everyMs)
        }
    }

    fun scanOnce() {
        val now = clock.instant()
        data class Due(val asset: UUID, val circle: UUID)
        val due = db.connection.use { c ->
            c.prepareStatement(
                """UPDATE checkins k SET raised_at = ? FROM assets a
                   WHERE a.id = k.asset_id AND k.answered_at IS NULL AND k.raised_at IS NULL AND k.sent_at <= ?
                   RETURNING k.asset_id, a.circle_id""",
            ).apply { setTimestamp(1, now.ts()); setTimestamp(2, now.minusMillis(CHECKIN_REPLY_MS).ts()) }
                .executeQuery().toList { Due(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java)) }
        }
        val reason = Reason("no_checkin_reply", mapOf("minutes" to (CHECKIN_REPLY_MS / 60_000).toString()))
        due.distinct().forEach { d ->
            if (recorder.appendToOpen(d.asset, reason)) return@forEach
            recorder.record(Finding(d.asset, d.circle, RuleId.NO_RESPONSE_CHECKIN, Tier.P1, listOf(reason), now))
        }
    }
}
