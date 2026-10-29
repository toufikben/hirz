package dz.hirz.server.sms

import dz.hirz.server.circles.audit
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.notify.AlertMessage
import dz.hirz.server.notify.Notifier
import dz.hirz.server.notify.PushTarget
import dz.hirz.server.ts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/** A gateway heartbeats every 5 minutes; three missed heartbeats make it silent. */
const val GATEWAY_SILENT_MS = 15 * 60_000L

/**
 * GATEWAY_SILENT (design §12.3.3): a silent SMS gateway means contacts would not be reached, so the circle's owners
 * get one quiet P2 notice per silence. It is not an incident: no asset is in danger because of it.
 */
class GatewaySilenceScanner(private val db: DataSource, private val clock: Clock, private val notifier: Notifier) {
    private val log = LoggerFactory.getLogger(GatewaySilenceScanner::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 60_000): Job = scope.launch {
        while (isActive) {
            runCatching { scanOnce() }.onFailure { log.error("gateway silence scan failed", it) }
            delay(everyMs)
        }
    }

    fun scanOnce() {
        val now = clock.instant()
        data class Silent(val gateway: UUID, val circle: UUID, val circleName: String)
        val silent = db.tx { c ->
            c.prepareStatement(
                """UPDATE gateways g SET silent_notified_at = ? FROM circles ci
                   WHERE ci.id = g.circle_id AND g.revoked_at IS NULL AND COALESCE(g.last_seen_at, g.created_at) < ?
                     AND (g.silent_notified_at IS NULL OR g.silent_notified_at <= COALESCE(g.last_seen_at, g.created_at))
                   RETURNING g.id, g.circle_id, ci.name""",
            ).apply { setTimestamp(1, now.ts()); setTimestamp(2, now.minusMillis(GATEWAY_SILENT_MS).ts()) }
                .executeQuery().toList { Silent(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getString(3)) }
                .onEach { audit(c, it.circle, null, "gateway_silent", """{"gateway":"${it.gateway}"}""") }
        }
        silent.forEach { s ->
            val owners = db.connection.use { c ->
                c.prepareStatement(
                    """SELECT pt.member_id, pt.token FROM circle_members cm JOIN push_tokens pt ON pt.member_id = cm.member_id
                       WHERE cm.circle_id = ? AND cm.role = 'owner'""",
                ).apply { setObject(1, s.circle) }.executeQuery().toList { it.getObject(1, UUID::class.java) to it.getString(2) }
            }
            runBlocking(Dispatchers.IO) {
                owners.map { (member, token) ->
                    async { runCatching { withTimeout(10_000) { notifier.send(PushTarget(member, token), AlertMessage("gateway_silent", null, "P2", s.circleName, null)) } } }
                }.awaitAll()
            }
        }
    }
}
