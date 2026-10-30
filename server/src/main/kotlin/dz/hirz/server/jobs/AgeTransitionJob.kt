package dz.hirz.server.jobs

import dz.hirz.server.assets.assetTypeOf
import dz.hirz.server.circles.audit
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.notify.AlertMessage
import dz.hirz.server.notify.Notifier
import dz.hirz.server.notify.PushTarget
import dz.hirz.server.ts
import dz.hirz.shared.asset.AssetType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/** Special needs is left out: an adult who may not be able to consent must not be cut off by a birthday. */
private val MINORS = setOf(AssetType.CHILD, AssetType.TEEN)

/**
 * Age transition (design §12.5.6). At 15 the owner is asked, once, to review what the child sees about being tracked.
 * At 18 the person is an adult: every source that has not got their own consent pauses (ingest answers 403
 * `consent_required`) until they give it on their phone. Assets without a birth date never transition.
 */
class AgeTransitionJob(private val db: DataSource, private val clock: Clock, private val notifier: Notifier) {
    private val log = LoggerFactory.getLogger(AgeTransitionJob::class.java)

    fun start(scope: CoroutineScope, everyMs: Long = 3_600_000): Job = scope.launch {
        while (isActive) {
            runCatching { runOnce() }.onFailure { log.error("age transition failed", it) }
            delay(everyMs)
        }
    }

    fun runOnce() {
        val now = clock.instant()
        val today = now.atZone(ZoneId.of("Africa/Algiers")).toLocalDate()
        data class Row(val asset: UUID, val circle: UUID, val name: String, val type: String, val birth: LocalDate, val noticed: Boolean)
        val rows = db.connection.use { c ->
            c.prepareStatement(
                "SELECT id, circle_id, name, type, birth_date, age_notice_15_at IS NOT NULL FROM assets WHERE birth_date IS NOT NULL AND lifecycle <> 'archived'",
            ).executeQuery().toList { Row(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getString(3), it.getString(4),
                it.getDate(5).toLocalDate(), it.getBoolean(6)) }
        }
        for (r in rows) {
            if (assetTypeOf(r.type) !in MINORS) continue
            val age = Period.between(r.birth, today).years
            if (age >= 18) {
                val paused = db.tx { c ->
                    val n = c.prepareStatement(
                        """UPDATE sources SET paused_reason = 'age_18' WHERE asset_id = ? AND revoked_at IS NULL AND paused_reason IS NULL
                           AND (adult = false OR consent_at IS NULL)""",
                    ).apply { setObject(1, r.asset) }.executeUpdate()
                    if (n > 0) audit(c, r.circle, null, "age_18_paused", """{"asset":"${r.asset}","sources":$n}""")
                    n
                }
                if (paused > 0) notifyOwners(r.circle, r.name, "age_paused")
            } else if (age >= 15 && !r.noticed) {
                db.tx { c ->
                    c.prepareStatement("UPDATE assets SET age_notice_15_at = ? WHERE id = ?").apply { setTimestamp(1, now.ts()); setObject(2, r.asset) }.executeUpdate()
                }
                notifyOwners(r.circle, r.name, "age_review")
            }
        }
    }

    private fun notifyOwners(circle: UUID, assetName: String, kind: String) {
        val targets = db.connection.use { c ->
            c.prepareStatement(
                """SELECT cm.member_id, pt.token FROM circle_members cm JOIN push_tokens pt ON pt.member_id = cm.member_id
                   WHERE cm.circle_id = ? AND cm.role = 'owner'""",
            ).apply { setObject(1, circle) }.executeQuery().toList { PushTarget(it.getObject(1, UUID::class.java), it.getString(2)) }
        }
        runBlocking { targets.forEach { t -> runCatching { notifier.send(t, AlertMessage(kind, null, null, assetName, null)) } } }
    }
}
