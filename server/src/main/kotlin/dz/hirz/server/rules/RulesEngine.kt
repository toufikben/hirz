package dz.hirz.server.rules

import dz.hirz.server.ingest.IngestListener
import dz.hirz.server.ingest.IngestedSignal
import dz.hirz.server.places.Geometry
import dz.hirz.server.places.PlaceService
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import dz.hirz.shared.rules.RuleConfig
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Runs the v0 rules on every accepted signal; boundary state is persisted per (asset, place). */
class RulesEngine(
    private val db: DataSource,
    private val cfg: RuleConfig,
    private val recorder: FindingRecorder,
) : IngestListener {

    override fun onIngested(s: IngestedSignal) {
        val at = Instant.ofEpochMilli(s.signal.serverTimeMs)
        Alarms.evaluate(s.signal.alarms, s.checkInReply).forEach { hit ->
            recorder.record(
                Finding(s.assetId, s.circleId, if (hit.duress) RuleId.DURESS else RuleId.SOS, Tier.P0, listOf(hit.reason), at, silent = hit.duress),
            )
        }
        if (s.cancelPin) recorder.appendToOpen(s.assetId, dz.hirz.shared.api.Reason("cancel_pin_entered"), RuleId.SOS)
        val findings = boundaries(s, at)
        if (findings.isEmpty()) return
        // Only PLACE_EXIT can be muted, and only by a handover that says so; SOS, duress and silence never are.
        val muted = findings.any { it.rule == RuleId.PLACE_EXIT } && db.connection.use { c -> dz.hirz.server.custody.Custody.muted(c, s.assetId, at) }
        findings.forEach { f -> if (muted && f.rule == RuleId.PLACE_EXIT) recorder.recordMuted(f) else recorder.record(f) }
    }

    private fun boundaries(s: IngestedSignal, at: Instant): List<Finding> {
        val position = s.signal.position ?: return emptyList()
        val fixMs = s.signal.fixTimeMs ?: return emptyList()
        if (s.quality != "ok" || !s.isLatestFix) return emptyList()
        val point = LatLon(position.lat, position.lon)
        val accuracy = position.accuracyM ?: Double.MAX_VALUE
        val found = mutableListOf<Finding>()
        db.connection.use { c ->
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
