package dz.hirz.server.vehicles

import dz.hirz.server.apiJson
import dz.hirz.server.assets.assetTypeOf
import dz.hirz.server.circles.CircleService
import dz.hirz.server.circles.audit
import dz.hirz.server.conflict
import dz.hirz.server.db.toList
import dz.hirz.server.db.tx
import dz.hirz.server.ingest.IngestListener
import dz.hirz.server.ingest.IngestedSignal
import dz.hirz.server.notFound
import dz.hirz.server.ts
import dz.hirz.server.unprocessable
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.AuthorizationDto
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.Role
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.api.VehicleStateDto
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.rules.RuleConfig
import kotlinx.serialization.builtins.ListSerializer
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

val VEHICLE_TYPES = setOf(AssetType.CAR, AssetType.MOTORCYCLE, AssetType.TRUCK, AssetType.BUS, AssetType.MACHINERY, AssetType.GENERATOR)
private val EDITORS = setOf(Role.OWNER, Role.GUARDIAN)
private val windows = ListSerializer(ActiveWindow.serializer())

/** The resolver's answer plus where the vehicle was when it was armed. */
data class ModeState(val mode: VehicleMode, val reason: String, val since: Instant?, val armedPosition: LatLon?, val ignition: Boolean?)

/**
 * Vehicle modes (design §12.1). The effective mode is resolved on demand ([ModeResolver]); every change is recorded
 * once in `mode_switches`, and arming stores the position the theft rules measure from. Disarming or authorizing while
 * a P0/P1 incident is open on the asset is refused: an unlocked stolen phone must not silence a theft.
 */
class VehicleModes(private val db: DataSource, private val clock: Clock, private val circles: CircleService, private val cfg: RuleConfig) {

    private fun circleOfVehicle(c: Connection, assetId: UUID): UUID {
        val (circle, type) = c.prepareStatement("SELECT circle_id, type FROM assets WHERE id = ?").apply { setObject(1, assetId) }
            .executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) to it.getString(2) else notFound() }
        if (assetTypeOf(type) !in VEHICLE_TYPES) unprocessable("not_a_vehicle")
        return circle
    }

    fun isVehicle(c: Connection, assetId: UUID): Boolean =
        c.prepareStatement("SELECT type FROM assets WHERE id = ?").apply { setObject(1, assetId) }.executeQuery()
            .use { it.next() && assetTypeOf(it.getString(1)) in VEHICLE_TYPES }

    /** Resolves the mode at [at], records a change once, and stores the armed position on arming. */
    fun current(c: Connection, assetId: UUID, at: Instant = clock.instant(), byMember: UUID? = null): ModeState {
        val (override, overrideAt) = c.prepareStatement("SELECT mode_override, mode_override_at FROM assets WHERE id = ?")
            .apply { setObject(1, assetId) }.executeQuery().use { it.next(); it.getString(1) to it.getTimestamp(2)?.toInstant() }
        val auths = c.prepareStatement("SELECT starts_at, ends_at FROM vehicle_authorizations WHERE asset_id = ? AND revoked_at IS NULL")
            .apply { setObject(1, assetId) }.executeQuery().toList { AuthorizationWindow(it.getTimestamp(1).toInstant(), it.getTimestamp(2).toInstant()) }
        val schedule = c.prepareStatement("SELECT schedule::text FROM asset_modes WHERE asset_id = ? AND mode = 'armed'")
            .apply { setObject(1, assetId) }.executeQuery().use { if (it.next()) apiJson.decodeFromString(windows, it.getString(1)) else emptyList() }
        data class Saved(val ignition: Boolean?, val ignitionSince: Instant?, val mode: String?, val lat: Double?, val lon: Double?)
        val saved = c.prepareStatement("SELECT ignition, ignition_since, mode, armed_lat, armed_lon FROM vehicle_state WHERE asset_id = ? FOR UPDATE")
            .apply { setObject(1, assetId) }.executeQuery().use {
                if (it.next()) Saved(it.getObject(1) as Boolean?, it.getTimestamp(2)?.toInstant(), it.getString(3),
                    it.getDouble(4).takeUnless { _ -> it.wasNull() }, it.getDouble(5).takeUnless { _ -> it.wasNull() })
                else Saved(null, null, null, null, null)
            }
        val o = override?.let { ModeOverride(VehicleMode.valueOf(it.uppercase()), overrideAt ?: at) }
        val r = ModeResolver.resolve(ModeInput(o, auths, schedule, saved.ignition, saved.ignitionSince), at, cfg)
        val wire = r.mode.name.lowercase()
        var armed = if (saved.lat != null && saved.lon != null) LatLon(saved.lat, saved.lon) else null
        if (saved.mode != wire) {
            armed = if (r.mode == VehicleMode.ARMED) lastFix(c, assetId) else null
            c.prepareStatement(
                """INSERT INTO vehicle_state (asset_id, mode, mode_since, mode_reason, armed_lat, armed_lon) VALUES (?, ?, ?, ?, ?, ?)
                   ON CONFLICT (asset_id) DO UPDATE SET mode = EXCLUDED.mode, mode_since = EXCLUDED.mode_since,
                     mode_reason = EXCLUDED.mode_reason, armed_lat = EXCLUDED.armed_lat, armed_lon = EXCLUDED.armed_lon""",
            ).apply {
                setObject(1, assetId); setString(2, wire); setTimestamp(3, (r.since ?: at).ts()); setString(4, r.reason)
                setObject(5, armed?.lat); setObject(6, armed?.lon)
            }.executeUpdate()
            c.prepareStatement("INSERT INTO mode_switches (asset_id, from_mode, to_mode, reason, by_member, at) VALUES (?, ?, ?, ?, ?, ?)").apply {
                setObject(1, assetId); setString(2, saved.mode); setString(3, wire); setString(4, r.reason); setObject(5, byMember); setTimestamp(6, at.ts())
            }.executeUpdate()
        } else if (r.mode == VehicleMode.ARMED && armed == null) {
            // Armed before any fix: the first fix after arming becomes the reference.
            armed = lastFix(c, assetId)?.also { p ->
                c.prepareStatement("UPDATE vehicle_state SET armed_lat = ?, armed_lon = ? WHERE asset_id = ?")
                    .apply { setDouble(1, p.lat); setDouble(2, p.lon); setObject(3, assetId) }.executeUpdate()
            }
        }
        return ModeState(r.mode, r.reason, r.since, armed, saved.ignition)
    }

    private fun lastFix(c: Connection, assetId: UUID): LatLon? =
        c.prepareStatement(
            """SELECT ST_Y(geog::geometry), ST_X(geog::geometry) FROM positions
               WHERE asset_id = ? AND quality = 'ok' AND geog IS NOT NULL ORDER BY fix_time DESC NULLS LAST LIMIT 1""",
        ).apply { setObject(1, assetId) }.executeQuery().use { if (it.next()) LatLon(it.getDouble(1), it.getDouble(2)) else null }

    private fun refuseDuringIncident(c: Connection, assetId: UUID) {
        val open = c.prepareStatement("SELECT EXISTS (SELECT 1 FROM incidents WHERE asset_id = ? AND state = 'open' AND tier IN ('P0', 'P1'))")
            .apply { setObject(1, assetId) }.executeQuery().use { it.next(); it.getBoolean(1) }
        if (open) conflict("incident_open")
    }

    fun setMode(assetId: UUID, memberId: UUID, mode: VehicleMode): VehicleStateDto = db.tx { c ->
        val circle = circleOfVehicle(c, assetId)
        circles.requireRole(c, circle, memberId, EDITORS)
        if (mode == VehicleMode.AUTHORIZED) unprocessable("use_authorizations")
        if (mode != VehicleMode.ARMED) refuseDuringIncident(c, assetId)
        val now = clock.instant()
        c.prepareStatement("UPDATE assets SET mode_override = ?, mode_override_at = ? WHERE id = ?")
            .apply { setString(1, mode.name.lowercase()); setTimestamp(2, now.ts()); setObject(3, assetId) }.executeUpdate()
        // An explicit arm ends any drive authorization still running.
        if (mode == VehicleMode.ARMED) c.prepareStatement("UPDATE vehicle_authorizations SET revoked_at = ? WHERE asset_id = ? AND revoked_at IS NULL AND ends_at > ?")
            .apply { setTimestamp(1, now.ts()); setObject(2, assetId); setTimestamp(3, now.ts()) }.executeUpdate()
        audit(c, circle, memberId, "vehicle_mode_set", """{"asset":"$assetId","mode":"${mode.name.lowercase()}"}""")
        dto(c, assetId, current(c, assetId, now, memberId))
    }

    fun authorize(assetId: UUID, memberId: UUID, hours: Int, driverName: String?, driverMember: UUID?): VehicleStateDto = db.tx { c ->
        val circle = circleOfVehicle(c, assetId)
        circles.requireRole(c, circle, memberId, EDITORS)
        if (hours !in 1..72) unprocessable("hours_1_to_72")
        refuseDuringIncident(c, assetId)
        val now = clock.instant()
        c.prepareStatement(
            "INSERT INTO vehicle_authorizations (asset_id, member_id, driver_name, starts_at, ends_at, created_by) VALUES (?, ?, ?, ?, ?, ?)",
        ).apply {
            setObject(1, assetId); setObject(2, driverMember); setString(3, driverName?.trim()?.take(80)); setTimestamp(4, now.ts())
            setTimestamp(5, now.plus(Duration.ofHours(hours.toLong())).ts()); setObject(6, memberId)
        }.executeUpdate()
        audit(c, circle, memberId, "vehicle_authorized", """{"asset":"$assetId","hours":$hours}""")
        dto(c, assetId, current(c, assetId, now, memberId))
    }

    fun revokeAuthorization(assetId: UUID, authorizationId: UUID, memberId: UUID): VehicleStateDto = db.tx { c ->
        val circle = circleOfVehicle(c, assetId)
        circles.requireRole(c, circle, memberId, EDITORS)
        val now = clock.instant()
        val n = c.prepareStatement("UPDATE vehicle_authorizations SET revoked_at = ? WHERE id = ? AND asset_id = ? AND revoked_at IS NULL")
            .apply { setTimestamp(1, now.ts()); setObject(2, authorizationId); setObject(3, assetId) }.executeUpdate()
        if (n == 0) notFound()
        audit(c, circle, memberId, "vehicle_authorization_revoked", """{"asset":"$assetId"}""")
        dto(c, assetId, current(c, assetId, now, memberId))
    }

    fun setSchedule(assetId: UUID, memberId: UUID, schedule: List<ActiveWindow>): VehicleStateDto = db.tx { c ->
        val circle = circleOfVehicle(c, assetId)
        circles.requireRole(c, circle, memberId, EDITORS)
        c.prepareStatement(
            """INSERT INTO asset_modes (asset_id, mode, schedule) VALUES (?, 'armed', ?::jsonb)
               ON CONFLICT (asset_id, mode) DO UPDATE SET schedule = EXCLUDED.schedule""",
        ).apply { setObject(1, assetId); setString(2, apiJson.encodeToString(windows, schedule)) }.executeUpdate()
        audit(c, circle, memberId, "vehicle_schedule_set", """{"asset":"$assetId","windows":${schedule.size}}""")
        dto(c, assetId, current(c, assetId, clock.instant(), memberId))
    }

    fun state(assetId: UUID, memberId: UUID): VehicleStateDto = db.tx { c ->
        circles.requireRole(c, circleOfVehicle(c, assetId), memberId)
        dto(c, assetId, current(c, assetId))
    }

    private fun dto(c: Connection, assetId: UUID, s: ModeState): VehicleStateDto {
        val now = clock.instant()
        val auth = c.prepareStatement(
            """SELECT id, starts_at, ends_at, driver_name, member_id FROM vehicle_authorizations
               WHERE asset_id = ? AND revoked_at IS NULL AND starts_at <= ? AND ends_at > ? ORDER BY ends_at DESC LIMIT 1""",
        ).apply { setObject(1, assetId); setTimestamp(2, now.ts()); setTimestamp(3, now.ts()) }.executeQuery().use {
            if (it.next()) AuthorizationDto(it.getString(1), it.getTimestamp(2).time, it.getTimestamp(3).time, it.getString(4), it.getString(5)) else null
        }
        val schedule = c.prepareStatement("SELECT schedule::text FROM asset_modes WHERE asset_id = ? AND mode = 'armed'")
            .apply { setObject(1, assetId) }.executeQuery().use { if (it.next()) apiJson.decodeFromString(windows, it.getString(1)) else emptyList() }
        val engineCut = c.prepareStatement("SELECT model FROM sources WHERE asset_id = ? AND revoked_at IS NULL AND model IS NOT NULL")
            .apply { setObject(1, assetId) }.executeQuery().toList { it.getString(1) }
            .any { dz.hirz.shared.devices.DeviceCatalog.byId(it)?.capabilities?.contains("engine_cut") == true }
        return VehicleStateDto(s.mode, s.reason, s.since?.toEpochMilli(), auth, s.armedPosition, s.ignition, schedule, engineCut)
    }
}

/** Keeps the ignition state and since-when for vehicles, from every report that carries it. */
class VehicleStateTracker(private val db: DataSource, private val modes: VehicleModes) : IngestListener {
    override fun onIngested(s: IngestedSignal) {
        val ignition = s.signal.ignition ?: return
        db.tx { c ->
            if (!modes.isVehicle(c, s.assetId)) return@tx
            val at = Instant.ofEpochMilli(s.signal.fixTimeMs ?: s.signal.serverTimeMs)
            c.prepareStatement(
                """INSERT INTO vehicle_state (asset_id, ignition, ignition_since) VALUES (?, ?, ?)
                   ON CONFLICT (asset_id) DO UPDATE SET ignition = EXCLUDED.ignition,
                     ignition_since = CASE WHEN vehicle_state.ignition IS DISTINCT FROM EXCLUDED.ignition THEN EXCLUDED.ignition_since ELSE vehicle_state.ignition_since END""",
            ).apply { setObject(1, s.assetId); setBoolean(2, ignition); setTimestamp(3, at.ts()) }.executeUpdate()
        }
    }
}
