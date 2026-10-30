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
     * `RUN_AWAY_SPEED`: [RuleConfig.runAwayFixes] good fixes in a row, outside home, each at least
     * [RuleConfig.runAwayMinStepM] farther from home than the last at [RuleConfig.runAwayKmh] or more. A coarse fix
     * neither counts nor resets the streak. Returns the next state and whether the rule fires now (once per outing).
     */
    fun runAway(st: RunAwayState, f: PersonFix, cfg: RuleConfig): Pair<RunAwayState, Boolean> {
        if (st.lastFixMs != null && f.fixMs <= st.lastFixMs) return st to false
        if (f.accuracyM > cfg.runAwayAccuracyMaxM) return st to false
        if (f.homeDistanceM <= 0.0) return RunAwayState(f.fixMs, f.homeDistanceM) to false
        val away = st.lastHomeDistanceM != null && f.homeDistanceM - st.lastHomeDistanceM >= cfg.runAwayMinStepM
        val fast = (f.speedKmh ?: 0.0) >= cfg.runAwayKmh
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
