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
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/** Limits for a remote engine stop: the last fix must be this fresh and this slow (design §12.3). */
data class EngineStopLimits(val maxFixAge: Duration = Duration.ofSeconds(60), val maxSpeedKmh: Double = 20.0)

/**
 * Remote engine stop and resume through the tracker's relay (design §12.3). Cutting a running engine at speed can kill,
 * so a stop needs every safeguard: the owning circle's owner, an open theft incident (or the vehicle reported missing),
 * a model that supports it, the device online, a fix under a minute old at walking speed, and a command that reached
 * the device now, never one queued to run later. Resume needs only the owner. Every attempt is recorded.
 */
class RemoteEngine(
    private val db: DataSource,
    private val clock: Clock,
    private val circles: CircleService,
    private val traccar: TraccarAdmin,
    private val limits: EngineStopLimits = EngineStopLimits(),
) {
    private class Tracker(val deviceId: Long, val model: String?)

    fun command(assetId: UUID, memberId: UUID, action: String): EngineResultDto = db.tx { c ->
        val stop = when (action) { "stop" -> true; "resume" -> false; else -> unprocessable("bad_action") }
        val (circle, lifecycle) = c.prepareStatement("SELECT circle_id, lifecycle FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) to it.getString(2) else notFound() }
        // Only the owning circle; a circle the asset is shared with sees the theft but does not cut the engine.
        val role = circles.requireRole(c, circle, memberId)
        val incident = theftIncident(c, assetId)
        fun done(outcome: String, reason: String?): EngineResultDto {
            record(c, assetId, circle, memberId, action, outcome, reason, incident)
            return EngineResultDto(outcome, reason)
        }
        if (role != Role.OWNER) return@tx done("refused", "not_owner")
        if (stop && incident == null && lifecycle != "missing") return@tx done("refused", "no_theft_incident")
        val tracker = tracker(c, assetId)
        if (tracker == null || DeviceCatalog.byId(tracker.model ?: "")?.capabilities?.contains("engine_cut") != true) return@tx done("refused", "not_supported")
        val type = if (stop) "engineStop" else "engineResume"
        if (!stop) {
            // A queued resume is safe: it lets the engine start whenever the device next connects.
            return@tx try { traccar.sendCommand(tracker.deviceId, type); done("sent", null) } catch (e: TraccarUnavailable) { done("refused", "traccar_unavailable") }
        }
        try {
            if (traccar.deviceStatus(tracker.deviceId) != "online") return@tx done("refused", "device_offline")
        } catch (e: TraccarUnavailable) { return@tx done("refused", "traccar_unavailable") }
        val fix = lastFix(c, assetId)
        if (fix == null || Duration.between(fix.first, clock.instant()) > limits.maxFixAge) return@tx done("refused", "stale_fix")
        if ((fix.second ?: 0.0) > limits.maxSpeedKmh) return@tx done("refused", "too_fast")
        val result = try { traccar.sendCommand(tracker.deviceId, type) } catch (e: TraccarUnavailable) { return@tx done("refused", "traccar_unavailable") }
        if (result == CommandResult.QUEUED) {
            // The device dropped between the status check and the send: a stop delivered later could hit a car at speed.
            runCatching { traccar.deleteQueued(tracker.deviceId) }
            return@tx done("refused", "queued_withdrawn")
        }
        done("sent", null)
    }

    private fun theftIncident(c: Connection, assetId: UUID): UUID? =
        c.prepareStatement(
            "SELECT id FROM incidents WHERE asset_id = ? AND state = 'open' AND rule IN ('ARMED_MOVEMENT', 'TOWING') ORDER BY opened_at DESC LIMIT 1",
        ).apply { setObject(1, assetId) }.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }

    private fun tracker(c: Connection, assetId: UUID): Tracker? =
        c.prepareStatement(
            """SELECT traccar_device_id, model FROM sources WHERE asset_id = ? AND revoked_at IS NULL AND traccar_device_id IS NOT NULL
               AND traccar_device_id > 0 ORDER BY created_at DESC LIMIT 1""",
        ).apply { setObject(1, assetId) }.executeQuery().use { if (it.next()) Tracker(it.getLong(1), it.getString(2)) else null }

    private fun lastFix(c: Connection, assetId: UUID): Pair<java.time.Instant, Double?>? =
        c.prepareStatement(
            "SELECT fix_time, speed_kmh FROM positions WHERE asset_id = ? AND geog IS NOT NULL AND fix_time IS NOT NULL ORDER BY fix_time DESC LIMIT 1",
        ).apply { setObject(1, assetId) }.executeQuery().use {
            if (it.next()) it.getTimestamp(1).toInstant() to it.getDouble(2).takeUnless { _ -> it.wasNull() } else null
        }

    private fun record(c: Connection, assetId: UUID, circle: UUID, memberId: UUID, action: String, outcome: String, reason: String?, incident: UUID?) {
        val now = clock.instant()
        c.prepareStatement(
            "INSERT INTO engine_commands (asset_id, member_id, incident_id, action, outcome, reason, at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).apply {
            setObject(1, assetId); setObject(2, memberId); setObject(3, incident); setString(4, action)
            setString(5, outcome); setString(6, reason); setTimestamp(7, now.ts())
        }.executeUpdate()
        val data = """{"asset":"$assetId","action":"$action","outcome":"$outcome"${reason?.let { ",\"reason\":\"$it\"" } ?: ""}}"""
        audit(c, circle, memberId, "engine_$action", data)
        if (incident != null) c.prepareStatement("INSERT INTO incident_events (incident_id, kind, member_id, data, at) VALUES (?, ?, ?, ?::jsonb, ?)")
            .apply { setObject(1, incident); setString(2, "engine_$action"); setObject(3, memberId); setString(4, data); setTimestamp(5, now.ts()) }
            .executeUpdate()
    }
}
