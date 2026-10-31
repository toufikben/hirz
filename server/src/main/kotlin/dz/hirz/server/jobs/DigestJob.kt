package dz.hirz.server.jobs

import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.notify.AlertMessage
import dz.hirz.server.notify.CONDITION_RULES
import dz.hirz.server.notify.Notifier
import dz.hirz.server.notify.PushTarget
import dz.hirz.server.ts
import dz.hirz.shared.api.DigestItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * The evening digest (design §7.4): at 20:00 Africa/Algiers, each circle's P2 findings of the day are gathered into one
 * row and one quiet push that names neither a place nor a rule. A day with nothing to tell writes and sends nothing.
 * Once per circle a day: the (circle, day) row is the latch, so a restart or a second run never sends twice.
 */
class DigestJob(private val db: DataSource, private val clock: Clock, private val notifier: Notifier) {
    private val log = LoggerFactory.getLogger(DigestJob::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 10 * 60_000L): Job = scope.launch {
        while (isActive) {
            runCatching { runOnce() }.onFailure { log.error("digest failed", it) }
            delay(everyMs)
        }
    }

    fun runOnce() {
        val local = clock.instant().atZone(ALGIERS)
        if (local.toLocalTime() < EVENING) return
        val day = local.toLocalDate()
        val from = day.atStartOfDay(ALGIERS).toInstant()
        val to = day.plusDays(1).atStartOfDay(ALGIERS).toInstant()
        val circles = db.connection.use { c ->
            c.prepareStatement(
                """SELECT DISTINCT a.circle_id FROM findings f JOIN assets a ON a.id = f.asset_id
                   WHERE f.tier = 'P2' AND f.at >= ? AND f.at < ?
                   AND NOT EXISTS (SELECT 1 FROM digests d WHERE d.circle_id = a.circle_id AND d.day = ?)""",
            ).apply { setTimestamp(1, from.ts()); setTimestamp(2, to.ts()); setObject(3, day) }
                .executeQuery().toList { it.getObject(1, UUID::class.java) }
        }
        for (circle in circles) runCatching { digest(circle, day, from, to) }.onFailure { log.error("digest $circle", it) }
    }

    private fun digest(circle: UUID, day: LocalDate, from: Instant, to: Instant) {
        val name = db.tx { c ->
            val items = c.prepareStatement(
                """SELECT a.id, a.name, f.rule, count(*), min(f.at) FROM findings f JOIN assets a ON a.id = f.asset_id
                   WHERE a.circle_id = ? AND f.tier = 'P2' AND f.at >= ? AND f.at < ?
                   GROUP BY a.id, a.name, f.rule ORDER BY min(f.at)""",
            ).apply { setObject(1, circle); setTimestamp(2, from.ts()); setTimestamp(3, to.ts()) }.executeQuery().toList {
                val rule = it.getString(3)
                DigestItem(it.getString(1), it.getString(2), if (rule in CONDITION_RULES) "PERSON_ALERT" else rule, it.getInt(4),
                    it.getTimestamp(5).toInstant().toEpochMilli())
            }
            if (items.isEmpty()) return@tx null
            val inserted = c.prepareStatement(
                "INSERT INTO digests (circle_id, day, items) VALUES (?, ?, ?::jsonb) ON CONFLICT (circle_id, day) DO NOTHING",
            ).apply { setObject(1, circle); setObject(2, day); setString(3, Json.encodeToString(items)) }.executeUpdate()
            if (inserted == 0) return@tx null
            c.prepareStatement("SELECT name FROM circles WHERE id = ?").apply { setObject(1, circle) }
                .executeQuery().use { if (it.next()) it.getString(1) else "" }
        } ?: return
        val targets = db.connection.use { c ->
            c.prepareStatement(
                """SELECT DISTINCT cm.member_id, pt.token FROM circle_members cm JOIN push_tokens pt ON pt.member_id = cm.member_id
                   WHERE cm.circle_id = ?""",
            ).apply { setObject(1, circle) }.executeQuery().toList { PushTarget(it.getObject(1, UUID::class.java), it.getString(2)) }
        }
        // Quiet: no tier, no rule, no place. The app opens the digest screen.
        runBlocking { targets.forEach { t -> runCatching { notifier.send(t, AlertMessage("digest", null, null, name, null)) } } }
    }

    companion object {
        private val ALGIERS = ZoneId.of("Africa/Algiers")
        private val EVENING = LocalTime.of(20, 0)
    }
}
