package dz.hirz.server.rules

import dz.hirz.server.ingest.IngestListener
import dz.hirz.server.ingest.IngestedSignal
import dz.hirz.server.places.Geometry
import dz.hirz.server.places.PlaceService
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import dz.hirz.shared.rules.RuleConfig
import dz.hirz.server.db.tx
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Runs the v0 rules on every accepted signal; boundary state is persisted per (asset, place). */
class RulesEngine(
    private val db: DataSource,
    private val cfg: RuleConfig,
    private val recorder: FindingRecorder,
    /** Vehicles follow their own rules (design §4.4); null in tests of the people rules alone. */
    private val vehicles: dz.hirz.server.vehicles.VehicleModes? = null,
) : IngestListener {

    override fun onIngested(s: IngestedSignal) {
        val at = Instant.ofEpochMilli(s.signal.serverTimeMs)
        Alarms.evaluate(s.signal.alarms, s.checkInReply).forEach { hit ->
            recorder.record(
                Finding(s.assetId, s.circleId, if (hit.duress) RuleId.DURESS else RuleId.SOS, Tier.P0, listOf(hit.reason), at, silent = hit.duress),
            )
        }
        if (s.cancelPin) recorder.appendToOpen(s.assetId, dz.hirz.shared.api.Reason("cancel_pin_entered"), RuleId.SOS)
        if (vehicles != null && vehicleRules(vehicles, s, at)) return
        val findings = boundaries(s, at)
        if (findings.isEmpty()) return
        // Only PLACE_EXIT can be muted, and only by a handover that says so; SOS, duress and silence never are.
        val muted = findings.any { it.rule == RuleId.PLACE_EXIT } && db.connection.use { c -> dz.hirz.server.custody.Custody.muted(c, s.assetId, at) }
        findings.forEach { f -> if (muted && f.rule == RuleId.PLACE_EXIT) recorder.recordMuted(f) else recorder.record(f) }
    }

    /**
     * Vehicle theft rules, under the same per-asset lock as the boundary state. Returns false for non-vehicles, which
     * then get the people rules; vehicles never get place exits (their profile, design §4.4).
     */
    private fun vehicleRules(vehicles: dz.hirz.server.vehicles.VehicleModes, s: IngestedSignal, at: Instant): Boolean {
        val hits = db.tx { c ->
            if (!vehicles.isVehicle(c, s.assetId)) return@tx null
            c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").apply { setString(1, s.assetId.toString()) }.executeQuery().close()
            val mode = vehicles.current(c, s.assetId, at)
            val st = c.prepareStatement("SELECT moving_count, last_fix_ms FROM vehicle_rule_state WHERE asset_id = ?")
                .apply { setObject(1, s.assetId) }.executeQuery().use {
                    if (it.next()) VehicleRuleState(it.getInt(1), it.getLong(2).takeUnless { _ -> it.wasNull() }) else VehicleRuleState()
                }
            val pos = s.signal.position
            val reading = VehicleReading(
                fixMs = s.signal.fixTimeMs, position = pos?.let { LatLon(it.lat, it.lon) }, accuracyM = pos?.accuracyM,
                speedKmh = s.signal.speedKmh, ignition = s.signal.ignition, alarms = s.signal.alarms,
                isLatestFix = s.isLatestFix && s.quality == "ok",
            )
            val (next, found) = VehicleRules.evaluate(st, mode.mode, mode.armedPosition, reading, cfg)
            // Any report ends a jamming-by-silence episode.
            c.prepareStatement(
                """INSERT INTO vehicle_rule_state (asset_id, moving_count, last_fix_ms, jamming_since) VALUES (?, ?, ?, NULL)
                   ON CONFLICT (asset_id) DO UPDATE SET moving_count = EXCLUDED.moving_count, last_fix_ms = EXCLUDED.last_fix_ms, jamming_since = NULL""",
            ).apply { setObject(1, s.assetId); setInt(2, next.movingCount); setObject(3, next.lastFixMs) }.executeUpdate()
            found
        } ?: return false
        hits.forEach { h -> recorder.record(Finding(s.assetId, s.circleId, h.rule, h.tier, listOf(h.reason), at)) }
        return true
    }

    private fun boundaries(s: IngestedSignal, at: Instant): List<Finding> {
        val position = s.signal.position ?: return emptyList()
        val fixMs = s.signal.fixTimeMs ?: return emptyList()
        if (s.quality != "ok" || !s.isLatestFix) return emptyList()
        val point = LatLon(position.lat, position.lon)
        val accuracy = position.accuracyM ?: Double.MAX_VALUE
        val found = mutableListOf<Finding>()
        db.tx { c ->
            // One evaluation per asset at a time: overlapping batches never lose a reading (P1 minor #11).
            c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").apply { setString(1, s.assetId.toString()) }.executeQuery().close()
            for (place in PlaceService.placesOf(c, s.circleId)) {
                val placeId = UUID.fromString(place.id)
                val state = load(c, s.assetId, placeId)
                val reading = Reading(fixMs, Geometry.distanceOutsideM(c, place.shape, point), accuracy, s.signal.serverTimeMs - fixMs)
                if (!PlaceBoundary.accepts(state, place, reading, cfg)) continue
                val result = PlaceBoundary.step(state, place, reading, Geometry.isActive(place.activeHours, at), cfg)
                save(c, s.assetId, placeId, result.state)
                result.reason?.let {
                    val rule = if (place.kind.alarmsOnEntry()) RuleId.FORBIDDEN_ENTRY else RuleId.PLACE_EXIT
                    found += Finding(s.assetId, s.circleId, rule, Tier.P1, listOf(it), at)
                }
            }
        }
        return found
    }

    private fun load(c: Connection, assetId: UUID, placeId: UUID): PlaceState =
        c.prepareStatement("SELECT status, outside_count, inside_count, last_fix_ms FROM asset_rule_state WHERE asset_id = ? AND place_id = ?")
            .apply { setObject(1, assetId); setObject(2, placeId) }.executeQuery().use { rs ->
                if (!rs.next()) PlaceState()
                else PlaceState(
                    BoundaryStatus.valueOf(rs.getString(1).uppercase()), rs.getInt(2), rs.getInt(3),
                    rs.getLong(4).takeUnless { rs.wasNull() },
                )
            }

    private fun save(c: Connection, assetId: UUID, placeId: UUID, st: PlaceState) {
        c.prepareStatement(
            """INSERT INTO asset_rule_state (asset_id, place_id, status, outside_count, inside_count, last_fix_ms) VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT (asset_id, place_id) DO UPDATE SET status = EXCLUDED.status, outside_count = EXCLUDED.outside_count,
                 inside_count = EXCLUDED.inside_count, last_fix_ms = EXCLUDED.last_fix_ms""",
        ).apply {
            setObject(1, assetId); setObject(2, placeId); setString(3, st.status.name.lowercase())
            setInt(4, st.outsideCount); setInt(5, st.insideCount); setObject(6, st.lastFixMs)
        }.executeUpdate()
    }
}
