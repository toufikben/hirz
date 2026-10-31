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
        collarCut(s, at)
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
            val (next, evaluated) = VehicleRules.evaluate(st, mode.mode, mode.armedPosition, reading, cfg)
            // Armed only because nobody's phone was heard (owner_away): a start gets a grace for the owner's phone to be
            // heard (a duty-cycled scan) before it counts as a theft. Power, jamming and antenna alarms never wait.
            val startedAt = if (mode.ignition == true) mode.ignitionSince ?: at else at
            val ownerAway = mode.mode == dz.hirz.shared.api.VehicleMode.ARMED && mode.reason == "owner_away" && reading.ignition == true
            val inGrace = ownerAway && at.isBefore(startedAt.plusMillis(cfg.ownerGraceMs))
            val waiting = setOf(RuleId.ARMED_MOVEMENT, RuleId.TOWING, RuleId.DOOR_OPEN_ARMED, RuleId.PARKED_TILT)
            val kept = if (inGrace) evaluated.filter { it.rule !in waiting } else evaluated
            // KEYLESS_NO_OWNER (P6): an armed car runs and no family phone has heard its tag (a relay attack). Only where
            // the family's phones do relay (heard in the last day), after the grace, once per start.
            val keylessNow = mode.ownerNearby == false && mode.relayActive && reading.ignition == true && !inGrace &&
                mode.mode == dz.hirz.shared.api.VehicleMode.ARMED
            val episode = startedAt.toEpochMilli()
            val keyless = keylessNow && PersonState.load(c, s.assetId, KEYLESS, KeylessState.serializer())?.startMs != episode
            if (keyless) PersonState.save(c, s.assetId, KEYLESS, KeylessState.serializer(), KeylessState(episode))
            val found = if (keyless) kept + VehicleHit(RuleId.KEYLESS_NO_OWNER, Tier.P1, dz.hirz.shared.api.Reason("keyless_no_owner")) else kept
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
        var lateTolerant = false
        db.tx { c ->
            // One evaluation per asset at a time: overlapping batches never lose a reading (P1 minor #11).
            c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").apply { setString(1, s.assetId.toString()) }.executeQuery().close()
            // Night windows are judged at the fix's own time: a phone that uploads its 02:00 fixes at 06:10 was out at 02:00.
            val fixAt = Instant.ofEpochMilli(fixMs)
            val profile = dz.hirz.server.assets.ProfileService.loadAt(c, s.assetId, fixAt) ?: return@tx
            lateTolerant = profile.lateTolerant
            val places = PlaceService.placesOf(c, s.circleId)
            val nightHomePlace = places.firstOrNull { it.kind == profile.nightHome }?.let { centre(it.shape) }
            val night = profile.isNight(fixAt, nightHomePlace)
            // Night exit watches home only, at night, for profiles that have it (design §4.3).
            val nightTier = profile.tierOf(RuleId.NIGHT_EXIT)?.takeIf { night }
            // Store-and-forward devices (collars in pastures without coverage) are judged on fixes hours old (P6).
            val placeCfg = if (profile.lateTolerant) cfg.copy(boundaryMaxAgeMs = cfg.lateMaxAgeMs) else cfg
            for (place in places) {
                val placeId = UUID.fromString(place.id)
                val state = load(c, s.assetId, placeId)
                val reading = Reading(fixMs, Geometry.distanceOutsideM(c, place.shape, point), accuracy, s.signal.serverTimeMs - fixMs)
                if (!PlaceBoundary.accepts(state, place, reading, placeCfg)) continue
                val homeAtNight = nightTier != null && place.kind == profile.nightHome
                val entryReadings = when (place.kind) {
                    PlaceKind.HAZARD -> profile.hazardEntryReadings
                    PlaceKind.COAST -> cfg.reentryReadings
                    else -> cfg.exitReadings
                }
                val exitReadings = if (homeAtNight) cfg.nightExitReadings else cfg.exitReadings
                // A coast place is judged below on its state, not on entry: being there at night is what matters.
                val active = if (place.kind == PlaceKind.COAST) false else Geometry.isActive(place.activeHours, at) || homeAtNight
                val result = PlaceBoundary.step(state, place, reading, active, placeCfg, entryReadings, exitReadings)
                save(c, s.assetId, placeId, result.state)
                if (place.kind == PlaceKind.COAST && night && result.state.status == BoundaryStatus.INSIDE) {
                    profile.tierOf(RuleId.COASTAL_NIGHT)?.let { tier ->
                        // Once per night and place, whether the teen walked in at 23:00 or was already there at dusk.
                        val key = "COAST:${place.id}"
                        val tonight = nightKey(fixAt)
                        if (PersonState.load(c, s.assetId, key, NightKey.serializer())?.night != tonight) {
                            PersonState.save(c, s.assetId, key, NightKey.serializer(), NightKey(tonight))
                            found += Finding(s.assetId, s.circleId, RuleId.COASTAL_NIGHT, tier, listOf(dz.hirz.shared.api.Reason("coastal_night", mapOf("place" to place.name))), at)
                        }
                    }
                }
                if (place.kind == profile.nightHome && result.state.status == BoundaryStatus.INSIDE) PersonState.clear(c, s.assetId, NIGHT_OUT)
                result.reason?.let {
                    if (homeAtNight && it.code == "left_place") {
                        found += nightExit(c, s, place, places, profile, nightTier!!, it, fixAt, at) ?: return@let
                        return@let
                    }
                    val rule = when {
                        place.kind == PlaceKind.HAZARD -> RuleId.HAZARD_PROXIMITY
                        place.kind.alarmsOnEntry() -> RuleId.FORBIDDEN_ENTRY
                        else -> RuleId.PLACE_EXIT
                    }
                    // The profile decides how urgent this is, or that it does not run for this asset at all.
                    profile.tierOf(rule)?.let { tier -> found += Finding(s.assetId, s.circleId, rule, tier, listOf(it), at) }
                }
            }
            // Livestock: the herd out of the barn at night, and a head carried at vehicle speed.
            found += herdNightMove(c, s, profile, places, fixAt, at, night)
            truckSpeed(c, s, profile, point, accuracy, fixAt, at)?.let { found += it }
            // Walking in circles outside home (elders): once per outing, re-armed at home.
            profile.tierOf(RuleId.PACING_LOOP)?.let { tier ->
                val atHome = places.filter { it.kind == PlaceKind.HOME }.any { Geometry.distanceOutsideM(c, it.shape, point) <= 0.0 }
                if (atHome) { PersonState.clear(c, s.assetId, PACING); return@let }
                if (PersonState.load(c, s.assetId, PACING, NightKey.serializer()) != null) return@let
                val track = c.prepareStatement(
                    """SELECT ST_Y(geog::geometry), ST_X(geog::geometry) FROM positions WHERE asset_id = ? AND quality = 'ok' AND geog IS NOT NULL
                       AND fix_time >= ? AND COALESCE(accuracy_m, 9999) <= ? ORDER BY fix_time""",
                ).apply { setObject(1, s.assetId); setTimestamp(2, java.sql.Timestamp.from(fixAt.minusMillis(cfg.pacingWindowMs))); setDouble(3, cfg.boundaryAccuracyFloorM) }
                    .executeQuery().use { rs -> buildList { while (rs.next()) add(LatLon(rs.getDouble(1), rs.getDouble(2))) } }
                if (PersonRules.pacing(track, cfg)) {
                    PersonState.save(c, s.assetId, PACING, NightKey.serializer(), NightKey(nightKey(fixAt)))
                    found += Finding(s.assetId, s.circleId, RuleId.PACING_LOOP, tier, listOf(dz.hirz.shared.api.Reason("pacing_loop")), at)
                }
            }
            // Running away from home at speed (autism): measured from the nearest home, never from a guardian's phone.
            profile.tierOf(RuleId.RUN_AWAY_SPEED)?.let { tier ->
                val homeM = places.filter { it.kind == PlaceKind.HOME }.minOfOrNull { Geometry.distanceOutsideM(c, it.shape, point) } ?: return@let
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
            // An excepted night outing (Fajr at the mosque) holds only on the way there and back, and only in its window.
            PersonState.load(c, s.assetId, NIGHT_OUT, NightOut.serializer())?.takeIf { !it.raised }?.let { out ->
                val home = places.firstOrNull { it.id == out.homeId } ?: return@let
                val target = places.firstOrNull { it.id == out.placeId } ?: return@let
                if (accuracy > cfg.boundaryAccuracyFloorM) return@let
                val fromHome = Geometry.distanceOutsideM(c, home.shape, point).coerceAtLeast(0.0)
                val fromTarget = Geometry.distanceOutsideM(c, target.shape, point)
                // On the way: inside an ellipse around home and the place, slack on each side. The other direction is not.
                val offRoute = fromTarget > 0.0 && fromHome + fromTarget > out.homeToTargetM + 2 * cfg.nightExceptionSlackM
                val overstayed = fromTarget > 0.0 && night && !Geometry.isActive(listOf(out.window), fixAt)
                if (offRoute || overstayed) {
                    PersonState.save(c, s.assetId, NIGHT_OUT, NightOut.serializer(), out.copy(raised = true))
                    found += Finding(s.assetId, s.circleId, RuleId.NIGHT_EXIT, out.tier, listOf(
                        dz.hirz.shared.api.Reason("night_exit_beyond", mapOf("place" to target.name, "distanceM" to fromHome.toInt().toString())),
                    ), at)
                }
            }
        }
        // Only store-and-forward profiles: a person's P0 reported over a slow network is still a P0.
        return if (lateTolerant) found.map { late(it, s.signal.serverTimeMs - fixMs) } else found
    }

    /** `COLLAR_CUT` (design §12.2): the device reports it was removed or cut. */
    private fun collarCut(s: IngestedSignal, at: Instant) {
        if (dz.hirz.shared.signal.AlarmKind.REMOVAL !in s.signal.alarms) return
        val tier = db.connection.use { c -> dz.hirz.server.assets.ProfileService.load(c, s.assetId) }?.tierOf(RuleId.COLLAR_CUT) ?: return
        recorder.record(Finding(s.assetId, s.circleId, RuleId.COLLAR_CUT, tier, listOf(dz.hirz.shared.api.Reason("collar_cut")), at))
    }

    /**
     * `HERD_NIGHT_MOVE`: at night, the herd's leader or at least [RuleConfig.herdNightShare] of its members with a fresh
     * fix are confirmed outside the barn. Raised once per night on the leader (or the reporting head).
     */
    private fun herdNightMove(
        c: Connection, s: IngestedSignal, profile: Profile, places: List<dz.hirz.shared.api.PlaceDto>, fixAt: Instant, at: Instant, night: Boolean,
    ): List<Finding> {
        val tier = profile.tierOf(RuleId.HERD_NIGHT_MOVE)?.takeIf { night } ?: return emptyList()
        val barns = places.filter { it.kind == PlaceKind.BARN }.takeIf { it.isNotEmpty() } ?: return emptyList()
        val herd = dz.hirz.server.herds.HerdService.herdOf(c, s.assetId) ?: return emptyList()
        val h = dz.hirz.server.herds.HerdService.load(c, herd) ?: return emptyList()
        val target = herdTarget(h)
        // Already ringing for this herd: nothing new. Once the family closes it, the next move rings again.
        if (openIncident(c, target, RuleId.HERD_NIGHT_MOVE)) return emptyList()
        // Every head's barn state in one query: heads confirmed outside every barn.
        val outsideAll = c.prepareStatement(
            """SELECT asset_id FROM asset_rule_state WHERE asset_id = ANY(?) AND place_id = ANY(?) AND status = 'outside'
               GROUP BY asset_id HAVING count(*) = ?""",
        ).apply {
            setArray(1, c.createArrayOf("uuid", h.members.toTypedArray())); setArray(2, c.createArrayOf("uuid", barns.map { it.id }.toTypedArray()))
            setInt(3, barns.size)
        }.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
        fun outside(asset: UUID) = asset in outsideAll
        // One query for the whole herd: which heads have a fresh fix.
        val fresh = c.prepareStatement(
            "SELECT DISTINCT asset_id FROM positions WHERE asset_id = ANY(?) AND quality = 'ok' AND fix_time >= ?",
        ).apply {
            setArray(1, c.createArrayOf("uuid", h.members.toTypedArray())); setTimestamp(2, java.sql.Timestamp.from(fixAt.minusMillis(cfg.herdFixMaxAgeMs)))
        }.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
        val leader = h.leaderId?.let(UUID::fromString)
        val out = fresh.count(::outside)
        // Before Eid al-Adha half the usual share is enough: thieves take a few heads, not the whole herd.
        val share = if (profile.eidSeason) cfg.herdNightShare / 2 else cfg.herdNightShare
        val moving = (leader != null && leader in fresh && outside(leader)) || (fresh.isNotEmpty() && out.toDouble() / fresh.size >= share)
        if (!moving) return emptyList()
        return listOf(Finding(target, s.circleId, RuleId.HERD_NIGHT_MOVE, tier, listOf(dz.hirz.shared.api.Reason("herd_night_move", mapOf(
            "herd" to h.name, "out" to out.toString(), "heads" to fresh.size.toString(),
        ))), at))
    }

    /** `VEHICLE_SPEED_UNEXPLAINED`: a head carried at vehicle speed outside its herd's market hours; P0 with a night exit open. */
    private fun truckSpeed(c: Connection, s: IngestedSignal, profile: Profile, point: LatLon, accuracy: Double, fixAt: Instant, at: Instant): Finding? {
        val tier = profile.tierOf(RuleId.VEHICLE_SPEED_UNEXPLAINED) ?: return null
        val market = dz.hirz.server.herds.HerdService.herdOf(c, s.assetId)?.let { dz.hirz.server.herds.HerdService.load(c, it) }
            ?.marketWindow?.takeIf { it.isNotEmpty() }?.let { Geometry.isActive(it, fixAt) } ?: false
        val st = PersonState.load(c, s.assetId, TRUCK, TruckState.serializer()) ?: TruckState()
        val (next, fire) = HerdRules.truck(st, fixAt.toEpochMilli(), point, s.signal.speedKmh, accuracy, market, cfg)
        PersonState.save(c, s.assetId, TRUCK, TruckState.serializer(), next)
        if (!fire) return null
        val nightOpen = openIncident(c, s.assetId, RuleId.NIGHT_EXIT) || openIncident(c, s.assetId, RuleId.HERD_NIGHT_MOVE) || herdAlarmOpen(c, s.assetId)
        return Finding(s.assetId, s.circleId, RuleId.VEHICLE_SPEED_UNEXPLAINED, if (nightOpen) Tier.P0 else tier,
            listOf(dz.hirz.shared.api.Reason("truck_speed", mapOf("speedKmh" to (s.signal.speedKmh ?: 0.0).toInt().toString()))), at)
    }

    /**
     * A finding from a fix that arrived late says how late, and never rings as a P0: the moment it describes is past,
     * and a P1 with the track is what the family can act on (P6).
     */
    private fun late(f: Finding, ageMs: Long): Finding {
        if (ageMs <= cfg.boundaryMaxAgeMs) return f
        val lateMin = (ageMs / 60_000).toString()
        return f.copy(
            // A collar that uploads every few minutes is live: only a report half an hour old loses its P0.
            tier = if (f.tier == Tier.P0 && ageMs > cfg.lateDemoteMs) Tier.P1 else f.tier,
            reasons = f.reasons.map { it.copy(args = it.args + ("lateMin" to lateMin)) },
        )
    }

    /**
     * A confirmed night exit from home: an incident, unless the owner expects this outing now (a night exception), in
     * which case it is remembered and raised only if the asset goes well past the excepted place.
     */
    private fun nightExit(
        c: Connection, s: IngestedSignal, home: dz.hirz.shared.api.PlaceDto, places: List<dz.hirz.shared.api.PlaceDto>,
        profile: Profile, tier: Tier, left: dz.hirz.shared.api.Reason, fixAt: Instant, at: Instant,
    ): Finding? {
        // A head leaving while its herd's night alarm is open is part of that alarm, not a new one.
        if (herdAlarmOpen(c, s.assetId)) return null
        val exception = profile.nightExceptions.firstOrNull { e -> Geometry.isActive(listOf(e.window), fixAt) && places.any { it.id == e.placeId } }
            ?: return Finding(s.assetId, s.circleId, RuleId.NIGHT_EXIT, tier, listOf(dz.hirz.shared.api.Reason("night_exit", left.args)), at)
        val target = places.first { it.id == exception.placeId }
        val d = Geometry.distanceOutsideM(c, home.shape, centre(target.shape))
        PersonState.save(c, s.assetId, NIGHT_OUT, NightOut.serializer(), NightOut(home.id, target.id, d, exception.window, tier))
        return null
    }

    /** The head a herd's alarms are raised on: the leader, or the same head every time. */
    private fun herdTarget(h: dz.hirz.shared.api.HerdDto): UUID = (h.leaderId ?: h.members.minOf { it }).let(UUID::fromString)

    private fun openIncident(c: Connection, assetId: UUID, rule: RuleId): Boolean =
        c.prepareStatement("SELECT EXISTS (SELECT 1 FROM incidents WHERE asset_id = ? AND state = 'open' AND rule = ?)")
            .apply { setObject(1, assetId); setString(2, rule.name) }.executeQuery().use { it.next(); it.getBoolean(1) }

    /** The herd's night alarm is open now: a head leaving is part of it, not a new incident. */
    private fun herdAlarmOpen(c: Connection, assetId: UUID): Boolean {
        val herd = dz.hirz.server.herds.HerdService.herdOf(c, assetId) ?: return false
        val h = dz.hirz.server.herds.HerdService.load(c, herd)?.takeIf { it.members.isNotEmpty() } ?: return false
        return openIncident(c, herdTarget(h), RuleId.HERD_NIGHT_MOVE)
    }

    /** The evening a night belongs to, as its local date: before noon it is still last night. */
    private fun nightKey(at: Instant): String {
        val local = at.atZone(java.time.ZoneId.of("Africa/Algiers"))
        return (if (local.hour < 12) local.toLocalDate().minusDays(1) else local.toLocalDate()).toString()
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
private const val TRUCK = "TRUCK_SPEED"
private const val KEYLESS = "KEYLESS"
private const val PACING = "PACING_LOOP"

/** The ignition start a keyless alarm was raised for. */
@kotlinx.serialization.Serializable
private data class KeylessState(val startMs: Long)

/** An excepted night outing in progress: from [homeId] toward [placeId] ([homeToTargetM] apart) during [window]. */
@kotlinx.serialization.Serializable
private data class NightOut(
    val homeId: String, val placeId: String, val homeToTargetM: Double, val window: dz.hirz.shared.api.ActiveWindow, val tier: Tier,
    val raised: Boolean = false,
)

@kotlinx.serialization.Serializable
private data class NightKey(val night: String)
