package dz.hirz.server.rules

import dz.hirz.shared.rules.RuleConfig
import kotlinx.serialization.Serializable

/** A good fix as the people rules see it: when, how far outside home (negative inside), how fast, how precise. */
data class PersonFix(val fixMs: Long, val homeDistanceM: Double, val speedKmh: Double?, val accuracyM: Double)

/** Running-away streak: the last good fix's distance from home and how many fast steps away in a row. */
@Serializable
data class RunAwayState(val lastFixMs: Long? = null, val lastHomeDistanceM: Double? = null, val count: Int = 0, val raised: Boolean = false)

/** P5 people rules (design §12.2), pure functions over persisted per-asset state. */
object PersonRules {
    /**
     * `PACING_LOOP` (elders, design §12.2): in the track (good fixes, oldest first), at least [RuleConfig.pacingPathM]
     * walked, all within [RuleConfig.pacingRadiusM] of its centre, and some [RuleConfig.pacingCellM] cell entered
     * [RuleConfig.pacingRevisits] times or more: walking in circles, a known sign of being lost.
     */
    fun pacing(track: List<dz.hirz.shared.api.LatLon>, cfg: RuleConfig): Boolean {
        if (track.size < 6) return false
        val path = track.zipWithNext { a, b -> dz.hirz.server.escalation.OwnerOrder.metres(a, b) }.sum()
        if (path < cfg.pacingPathM) return false
        val centre = dz.hirz.shared.api.LatLon(track.map { it.lat }.average(), track.map { it.lon }.average())
        if (track.any { dz.hirz.server.escalation.OwnerOrder.metres(centre, it) > cfg.pacingRadiusM }) return false
        val cosLat = kotlin.math.cos(Math.toRadians(centre.lat))
        val cells = track.map { (it.lat * 111_320.0 / cfg.pacingCellM).toLong() to (it.lon * 111_320.0 * cosLat / cfg.pacingCellM).toLong() }
        // Entries into a cell, not fixes in it: standing still in one cell is not pacing.
        val entries = cells.filterIndexed { i, c -> i == 0 || cells[i - 1] != c }.groupingBy { it }.eachCount()
        return (entries.values.maxOrNull() ?: 0) >= cfg.pacingRevisits
    }

    /**
     * `RUN_AWAY_SPEED`: [RuleConfig.runAwayFixes] good fixes in a row, outside home, each at least
     * [RuleConfig.runAwayMinStepM] farther from home than the last at [RuleConfig.runAwayKmh] or more. A coarse fix
     * neither counts nor resets the streak. Returns the next state and whether the rule fires now (once per outing).
     */
    fun runAway(st: RunAwayState, f: PersonFix, cfg: RuleConfig): Pair<RunAwayState, Boolean> {
        if (st.lastFixMs != null && f.fixMs <= st.lastFixMs) return st to false
        if (f.accuracyM > cfg.runAwayAccuracyMaxM) return st to false
        if (f.homeDistanceM <= 0.0) return RunAwayState(f.fixMs, f.homeDistanceM) to false
        val step = st.lastHomeDistanceM?.let { f.homeDistanceM - it }
        val dtS = st.lastFixMs?.let { (f.fixMs - it) / 1000.0 }
        // Half of what a run covers between these two fixes, between 5 m (fixes every few seconds) and the configured step.
        val minStep = dtS?.let { (0.5 * cfg.runAwayKmh / 3.6 * it).coerceIn(5.0, cfg.runAwayMinStepM) } ?: cfg.runAwayMinStepM
        val away = step != null && step >= minStep
        // Many trackers send no speed: derive it from the step away.
        val speed = f.speedKmh ?: if (step != null && dtS != null && dtS > 0) step / dtS * 3.6 else 0.0
        val fast = speed >= cfg.runAwayKmh
        val count = if (away && fast) st.count + 1 else 0
        val fire = !st.raised && count >= cfg.runAwayFixes
        return RunAwayState(f.fixMs, f.homeDistanceM, count, st.raised || fire) to fire
    }

    /**
     * `PAST_ADDRESS_HEADING`: outside home, [RuleConfig.pastAddressHeadingFixes] good fixes in a row each closer to the
     * same past address, together at least [RuleConfig.pastAddressCloserM] closer. [distances]: place id → metres
     * outside it. Returns the next state and the place ids that fire now (each once per outing).
     */
    fun pastAddress(st: PastAddressState, fixMs: Long, outsideHome: Boolean, accuracyM: Double, distances: Map<String, Double>, cfg: RuleConfig): Pair<PastAddressState, List<String>> {
        if (st.lastFixMs != null && fixMs <= st.lastFixMs) return st to emptyList()
        if (accuracyM > cfg.boundaryAccuracyFloorM) return st to emptyList()
        if (!outsideHome) return PastAddressState(lastFixMs = fixMs) to emptyList()
        val fired = mutableListOf<String>()
        val streaks = distances.mapValues { (id, d) ->
            val prev = st.streaks[id]
            val closer = prev != null && prev.lastM - d >= cfg.pastAddressMinStepM
            val next = if (closer) prev!!.copy(lastM = d, count = prev.count + 1) else Streak(d, d, 0)
            if (id !in st.raised && next.count >= cfg.pastAddressHeadingFixes && next.startM - d >= cfg.pastAddressCloserM) fired += id
            next
        }
        return PastAddressState(fixMs, streaks, st.raised + fired) to fired
    }
}

@Serializable
data class Streak(val startM: Double, val lastM: Double, val count: Int)

/** Per past address: the distance where the current approach started, the last distance, and steps closer in a row. */
@Serializable
data class PastAddressState(val lastFixMs: Long? = null, val streaks: Map<String, Streak> = emptyMap(), val raised: Set<String> = emptySet())
