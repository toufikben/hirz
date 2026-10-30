package dz.hirz.server.vehicles

import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.db.tx
import dz.hirz.server.notFound
import dz.hirz.server.traccar.CommandResult
import dz.hirz.server.traccar.TraccarAdmin
import dz.hirz.server.traccar.TraccarUnavailable
import dz.hirz.server.ts
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.EngineResultDto
import dz.hirz.shared.api.Role
import dz.hirz.shared.devices.DeviceCatalog
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Limits for a remote engine stop: the last fix must be this fresh and this slow (design §12.3). */
data class EngineStopLimits(
    val maxFixAge: Duration = Duration.ofSeconds(60),
    val maxSpeedKmh: Double = 20.0,
    /** Device clocks run a little ahead; more than this and the fix is not trusted. */
    val maxClockSkew: Duration = Duration.ofSeconds(5),
)

/**
 * Remote engine stop and resume through the tracker's relay (design §12.3). Cutting a running engine at speed can kill,
 * so a stop needs every safeguard: the owning circle's owner, an open theft incident (or the vehicle reported missing),
 * a model that supports it, a Traccar that can refuse to queue (6.7+), the device online, a fix from the relay's own
 * tracker under a minute old with a known speed of walking pace or less, and a command that reached the device now.
 * Resume needs only the owner.
 *
 * Every attempt is recorded. A command that passes the checks is first recorded as pending and committed; Traccar is
 * called outside any transaction; the outcome is written after. A timeout after the send is recorded as unknown, never
 * as refused, because the device may have received it.
 */
class RemoteEngine(
    private val db: DataSource,
    private val clock: Clock,
    private val circles: CircleService,
    private val traccar: TraccarAdmin,
    private val limits: EngineStopLimits = EngineStopLimits(),
) {
    private val log = LoggerFactory.getLogger(RemoteEngine::class.java)

    private class Tracker(val sourceId: UUID, val deviceId: Long, val model: String?)
    private class Approved(val rowId: Long, val circle: UUID, val incident: UUID?, val deviceId: Long)

    fun command(assetId: UUID, memberId: UUID, action: String): EngineResultDto {
        val stop = when (action) { "stop" -> true; "resume" -> false; else -> unprocessable("bad_action") }
        val approved = when (val checked = check(assetId, memberId, action, stop)) {
            is EngineResultDto -> return checked
            else -> checked as Approved
        }
        val type = if (stop) "engineStop" else "engineResume"
        val (outcome, reason) = try {
            when (traccar.sendCommand(approved.deviceId, type)) {
                CommandResult.SENT -> "sent" to null
                // A queued resume is safe: it lets the engine start whenever the device next connects.
                CommandResult.QUEUED -> if (!stop) "sent" to null else withdraw(approved)
            }
        } catch (e: TraccarUnavailable) {
            log.error("engine $action to device ${approved.deviceId}: outcome unknown", e)
            "unknown" to "traccar_unavailable"
        }
        finish(approved, assetId, memberId, action, outcome, reason)
        return EngineResultDto(outcome, reason)
    }

    /** The device dropped between the status check and the send: a stop delivered later could hit a car at speed. */
    private fun withdraw(a: Approved): Pair<String, String> {
        log.error("engine stop to device ${a.deviceId} was queued by Traccar despite noQueue; countering it")
        return try {
            traccar.deleteQueued(a.deviceId)
            "refused" to "queued_withdrawn"
        } catch (e: Exception) {
            log.error("engine stop to device ${a.deviceId}: withdrawal failed, a cut may run on reconnect", e)
            "unknown" to "withdraw_failed"
        }
    }

    /** Every safeguard short of the send. A refusal is recorded and returned; a pass is recorded as pending. */
    private fun check(assetId: UUID, memberId: UUID, action: String, stop: Boolean): Any = db.tx { c ->
        val (circle, lifecycle) = c.prepareStatement("SELECT circle_id, lifecycle FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) to it.getString(2) else notFound() }
        // Only the owning circle; a circle the asset is shared with sees the theft but does not cut the engine.
        val role = circles.requireRole(c, circle, memberId)
        val incident = theftIncident(c, assetId)
        fun refuse(reason: String): EngineResultDto {
            insert(c, assetId, circle, memberId, incident, action, "refused", reason)
            audit(c, circle, memberId, "engine_$action", data(assetId, action, "refused", reason))
            incident?.let { event(c, it, memberId, action, data(assetId, action, "refused", reason)) }
            return EngineResultDto("refused", reason)
        }
        if (role != Role.OWNER) return@tx refuse("not_owner")
        if (stop && incident == null && lifecycle != "missing") return@tx refuse("no_theft_incident")
        val tracker = tracker(c, assetId)
        if (tracker == null || DeviceCatalog.byId(tracker.model ?: "")?.capabilities?.contains("engine_cut") != true) return@tx refuse("not_supported")
        if (stop) {
            val version = runCatching { traccar.serverVersion() }.getOrNull()
            if (!queueRefusable(version)) return@tx refuse("gateway_unsupported")
            val status = try { traccar.deviceStatus(tracker.deviceId) } catch (e: TraccarUnavailable) { return@tx refuse("traccar_unavailable") }
            if (status != "online") return@tx refuse("device_offline")
            val fix = lastFix(c, tracker.sourceId) ?: return@tx refuse("stale_fix")
            val age = Duration.between(fix.first, clock.instant())
            if (age > limits.maxFixAge || age < limits.maxClockSkew.negated()) return@tx refuse("stale_fix")
            val speed = fix.second ?: return@tx refuse("no_speed")
            if (speed > limits.maxSpeedKmh) return@tx refuse("too_fast")
        }
        Approved(insert(c, assetId, circle, memberId, incident, action, "pending", null), circle, incident, tracker.deviceId)
    }

    private fun finish(a: Approved, assetId: UUID, memberId: UUID, action: String, outcome: String, reason: String?) = db.tx { c ->
        c.prepareStatement("UPDATE engine_commands SET outcome = ?, reason = ? WHERE id = ?")
            .apply { setString(1, outcome); setString(2, reason); setLong(3, a.rowId) }.executeUpdate()
        val d = data(assetId, action, outcome, reason)
        audit(c, a.circle, memberId, "engine_$action", d)
        a.incident?.let { event(c, it, memberId, action, d) }
    }

    /** Traccar 6.7 added noQueue; before it, a stop to a device that just dropped would wait in the queue. */
    private fun queueRefusable(version: String?): Boolean {
        val parts = version?.split('.')?.mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() } ?: return false
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        return major > 6 || (major == 6 && minor >= 7)
    }

    private fun theftIncident(c: Connection, assetId: UUID): UUID? =
        c.prepareStatement(
            "SELECT id FROM incidents WHERE asset_id = ? AND state = 'open' AND rule IN ('ARMED_MOVEMENT', 'TOWING') ORDER BY opened_at DESC LIMIT 1",
        ).apply { setObject(1, assetId) }.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }

    private fun tracker(c: Connection, assetId: UUID): Tracker? =
        c.prepareStatement(
            """SELECT id, traccar_device_id, model FROM sources WHERE asset_id = ? AND revoked_at IS NULL AND traccar_device_id IS NOT NULL
               AND traccar_device_id > 0 ORDER BY created_at DESC LIMIT 1""",
        ).apply { setObject(1, assetId) }.executeQuery().use {
            if (it.next()) Tracker(it.getObject(1, UUID::class.java), it.getLong(2), it.getString(3)) else null
        }

    /** The relay tracker's own newest usable fix: another source on the asset says nothing about this device's car. */
    private fun lastFix(c: Connection, sourceId: UUID): Pair<Instant, Double?>? =
        c.prepareStatement(
            """SELECT fix_time, speed_kmh FROM positions WHERE source_id = ? AND quality = 'ok' AND geog IS NOT NULL AND fix_time IS NOT NULL
               ORDER BY fix_time DESC LIMIT 1""",
        ).apply { setObject(1, sourceId) }.executeQuery().use {
            if (it.next()) it.getTimestamp(1).toInstant() to it.getDouble(2).takeUnless { _ -> it.wasNull() } else null
        }

    private fun data(assetId: UUID, action: String, outcome: String, reason: String?) =
        """{"asset":"$assetId","action":"$action","outcome":"$outcome"${reason?.let { ",\"reason\":\"$it\"" } ?: ""}}"""

    private fun insert(c: Connection, assetId: UUID, circle: UUID, memberId: UUID, incident: UUID?, action: String, outcome: String, reason: String?): Long =
        c.prepareStatement(
            "INSERT INTO engine_commands (asset_id, member_id, incident_id, action, outcome, reason, at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
        ).apply {
            setObject(1, assetId); setObject(2, memberId); setObject(3, incident); setString(4, action)
            setString(5, outcome); setString(6, reason); setTimestamp(7, clock.instant().ts())
        }.executeQuery().use { it.next(); it.getLong(1) }

    private fun event(c: Connection, incident: UUID, memberId: UUID, action: String, data: String) {
        c.prepareStatement("INSERT INTO incident_events (incident_id, kind, member_id, data, at) VALUES (?, ?, ?, ?::jsonb, ?)")
            .apply { setObject(1, incident); setString(2, "engine_$action"); setObject(3, memberId); setString(4, data); setTimestamp(5, clock.instant().ts()) }
            .executeUpdate()
    }
}
