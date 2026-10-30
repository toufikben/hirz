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
}
