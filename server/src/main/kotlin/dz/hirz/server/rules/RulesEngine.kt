package dz.hirz.server.rules

import dz.hirz.server.ingest.IngestListener
import dz.hirz.server.ingest.IngestedSignal
import dz.hirz.server.places.Geometry
import dz.hirz.server.places.PlaceService
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.PlaceKind
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
            val profile = dz.hirz.server.assets.ProfileService.load(c, s.assetId) ?: return@tx
            // Night exit watches home only, at night, for profiles that have it (design §4.3).
            val nightTier = profile.tierOf(RuleId.NIGHT_EXIT)?.takeIf { Geometry.isActive(profile.nightWindow, at) }
            val places = PlaceService.placesOf(c, s.circleId)
            for (place in places) {
                val placeId = UUID.fromString(place.id)
                val state = load(c, s.assetId, placeId)
                val reading = Reading(fixMs, Geometry.distanceOutsideM(c, place.shape, point), accuracy, s.signal.serverTimeMs - fixMs)
                if (!PlaceBoundary.accepts(state, place, reading, cfg)) continue
                val homeAtNight = nightTier != null && place.kind == PlaceKind.HOME
                val entryReadings = when (place.kind) {
                    PlaceKind.HAZARD -> profile.hazardEntryReadings
                    PlaceKind.COAST -> cfg.reentryReadings
                    else -> cfg.exitReadings
                }
                val exitReadings = if (homeAtNight) cfg.nightExitReadings else cfg.exitReadings
                // A coast place matters only at night; by day the beach is just the beach.
                val active = if (place.kind == PlaceKind.COAST) Geometry.isActive(profile.nightWindow, at)
                    else Geometry.isActive(place.activeHours, at) || homeAtNight
                val result = PlaceBoundary.step(state, place, reading, active, cfg, entryReadings, exitReadings)
                save(c, s.assetId, placeId, result.state)
                if (place.kind == PlaceKind.HOME && result.state.status == BoundaryStatus.INSIDE) PersonState.clear(c, s.assetId, NIGHT_OUT)
                result.reason?.let {
                    if (homeAtNight && it.code == "left_place") {
                        found += nightExit(c, s, place, places, profile, nightTier!!, it, at) ?: return@let
                        return@let
                    }
                    val rule = when {
                        place.kind == PlaceKind.HAZARD -> RuleId.HAZARD_PROXIMITY
                        place.kind == PlaceKind.COAST -> RuleId.COASTAL_NIGHT
                        place.kind.alarmsOnEntry() -> RuleId.FORBIDDEN_ENTRY
                        else -> RuleId.PLACE_EXIT
                    }
                    // The profile decides how urgent this is, or that it does not run for this asset at all.
                    profile.tierOf(rule)?.let { tier -> found += Finding(s.assetId, s.circleId, rule, tier, listOf(it), at) }
                }
            }
            // Running away from home at speed (autism): measured from home, never from a guardian's phone.
            profile.tierOf(RuleId.RUN_AWAY_SPEED)?.let { tier ->
                val home = places.firstOrNull { it.kind == PlaceKind.HOME } ?: return@let
                val homeM = Geometry.distanceOutsideM(c, home.shape, point)
                val st = PersonState.load(c, s.assetId, RUN_AWAY, RunAwayState.serializer()) ?: RunAwayState()
                val (next, fire) = PersonRules.runAway(st, PersonFix(fixMs, homeM, s.signal.speedKmh, accuracy), cfg)
                PersonState.save(c, s.assetId, RUN_AWAY, RunAwayState.serializer(), next)
                if (fire) found += Finding(s.assetId, s.circleId, RuleId.RUN_AWAY_SPEED, tier, listOf(dz.hirz.shared.api.Reason("running_away", mapOf(
                    "distanceM" to homeM.toInt().toString(), "speedKmh" to (s.signal.speedKmh ?: 0.0).toInt().toString(),
                ))), at)
            }
            // Heading toward an old address (dementia), a known wandering pattern.
            profile.tierOf(RuleId.PAST_ADDRESS_HEADING)?.let { tier ->
                val home = places.firstOrNull { it.kind == PlaceKind.HOME } ?: return@let
                val targets = places.filter { it.kind == PlaceKind.PAST_ADDRESS }.takeIf { it.isNotEmpty() } ?: return@let
                val outside = Geometry.distanceOutsideM(c, home.shape, point) > 0.0
                val distances = targets.associate { it.id to Geometry.distanceOutsideM(c, it.shape, point) }
                val st = PersonState.load(c, s.assetId, PAST_ADDRESS, PastAddressState.serializer()) ?: PastAddressState()
                val (next, fired) = PersonRules.pastAddress(st, fixMs, outside, accuracy, distances, cfg)
                PersonState.save(c, s.assetId, PAST_ADDRESS, PastAddressState.serializer(), next)
                fired.forEach { id ->
                    val place = targets.first { it.id == id }
                    found += Finding(s.assetId, s.circleId, RuleId.PAST_ADDRESS_HEADING, tier, listOf(dz.hirz.shared.api.Reason("heading_past_address", mapOf(
                        "place" to place.name, "distanceM" to distances.getValue(id).toInt().toString(),
                    ))), at)
                }
            }
            // An excepted night outing (Fajr at the mosque) still raises once it goes well past where it was headed.
            PersonState.load(c, s.assetId, NIGHT_OUT, NightOut.serializer())?.takeIf { !it.raised }?.let { out ->
                val home = places.firstOrNull { it.id == out.homeId } ?: return@let
                val beyond = Geometry.distanceOutsideM(c, home.shape, point)
                if (accuracy <= cfg.boundaryAccuracyFloorM && beyond > out.limitM) {
                    PersonState.save(c, s.assetId, NIGHT_OUT, NightOut.serializer(), out.copy(raised = true))
                    found += Finding(s.assetId, s.circleId, RuleId.NIGHT_EXIT, out.tier, listOf(
                        dz.hirz.shared.api.Reason("night_exit_beyond", mapOf("place" to out.placeName, "distanceM" to beyond.toInt().toString())),
                    ), at)
                }
            }
        }
        return found
    }

    /**
     * A confirmed night exit from home: an incident, unless the owner expects this outing now (a night exception), in
     * which case it is remembered and raised only if the asset goes well past the excepted place.
     */
    private fun nightExit(
        c: Connection, s: IngestedSignal, home: dz.hirz.shared.api.PlaceDto, places: List<dz.hirz.shared.api.PlaceDto>,
        profile: Profile, tier: Tier, left: dz.hirz.shared.api.Reason, at: Instant,
    ): Finding? {
        val exception = profile.nightExceptions.firstOrNull { e -> Geometry.isActive(listOf(e.window), at) && places.any { it.id == e.placeId } }
            ?: return Finding(s.assetId, s.circleId, RuleId.NIGHT_EXIT, tier, listOf(dz.hirz.shared.api.Reason("night_exit", left.args)), at)
        val target = places.first { it.id == exception.placeId }
        val limit = Geometry.distanceOutsideM(c, home.shape, centre(target.shape)) + cfg.nightExceptionSlackM
        PersonState.save(c, s.assetId, NIGHT_OUT, NightOut.serializer(), NightOut(home.id, target.name, limit, tier))
        return null
    }

    private fun centre(shape: dz.hirz.shared.api.ShapeDto): LatLon = when (shape) {
        is dz.hirz.shared.api.ShapeDto.Circle -> shape.center
        is dz.hirz.shared.api.ShapeDto.Polygon -> LatLon(shape.points.map { it.lat }.average(), shape.points.map { it.lon }.average())
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

private const val NIGHT_OUT = "NIGHT_OUT"
private const val RUN_AWAY = "RUN_AWAY_SPEED"
private const val PAST_ADDRESS = "PAST_ADDRESS_HEADING"

/** An excepted night outing in progress: raise if it goes farther from home than [limitM]. */
@kotlinx.serialization.Serializable
private data class NightOut(val homeId: String, val placeName: String, val limitM: Double, val tier: Tier, val raised: Boolean = false)
