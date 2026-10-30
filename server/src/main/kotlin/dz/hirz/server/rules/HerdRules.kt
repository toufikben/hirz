package dz.hirz.server.rules

import dz.hirz.server.escalation.OwnerOrder
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.rules.RuleConfig
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** Truck-speed streak: since when good fixes have been at vehicle speed, the last good fix, and whether it fired. */
@Serializable
data class TruckState(val sinceMs: Long? = null, val lastFixMs: Long? = null, val lastLat: Double? = null, val lastLon: Double? = null, val raised: Boolean = false)

/** Livestock rules (design §4.3, §12.2), pure. */
object HerdRules {
    /** Root mean square distance of [points] from their centroid, in metres. */
    fun radiusOfGyrationM(points: List<LatLon>): Double {
        val c = LatLon(points.map { it.lat }.average(), points.map { it.lon }.average())
        return sqrt(points.map { OwnerOrder.metres(c, it).let { d -> d * d } }.average())
    }

    /** The spread [now] is at least [RuleConfig.herdDispersionM] and [RuleConfig.herdDispersionFactor]× the tightest recent sample. */
    fun dispersed(recent: List<Double>, now: Double, cfg: RuleConfig): Boolean {
        val base = recent.minOrNull() ?: return false
        return now >= cfg.herdDispersionM && now >= cfg.herdDispersionFactor * maxOf(base, 1.0)
    }

    /** At least [RuleConfig.stillMinFixes] good fixes spanning [RuleConfig.stillMs], all within [RuleConfig.stillRadiusM] of their centroid. */
    fun still(track: List<Pair<Long, LatLon>>, cfg: RuleConfig): Boolean {
        if (track.size < cfg.stillMinFixes) return false
        if (track.last().first - track.first().first < cfg.stillMs) return false
        val c = LatLon(track.map { it.second.lat }.average(), track.map { it.second.lon }.average())
        return track.all { OwnerOrder.metres(c, it.second) <= cfg.stillRadiusM }
    }

    /**
     * `VEHICLE_SPEED_UNEXPLAINED`: good fixes at [RuleConfig.truckKmh] or more for [RuleConfig.truckMs]. The speed is the
     * device's, or derived from the last good fix. [excused] (a market day) resets the streak.
     */
    fun truck(st: TruckState, fixMs: Long, at: LatLon, speedKmh: Double?, accuracyM: Double, excused: Boolean, cfg: RuleConfig): Pair<TruckState, Boolean> {
        if (st.lastFixMs != null && fixMs <= st.lastFixMs) return st to false
        if (accuracyM > cfg.boundaryAccuracyFloorM) return st to false
        val derived = if (st.lastFixMs != null && st.lastLat != null && st.lastLon != null) {
            val dt = (fixMs - st.lastFixMs) / 1000.0
            if (dt > 0) OwnerOrder.metres(LatLon(st.lastLat, st.lastLon), at) / dt * 3.6 else null
        } else null
        val speed = speedKmh ?: derived ?: 0.0
        val fast = !excused && speed >= cfg.truckKmh
        val since = if (fast) st.sinceMs ?: fixMs else null
        val fire = fast && !st.raised && fixMs - since!! >= cfg.truckMs
        return TruckState(since, fixMs, at.lat, at.lon, if (fast) st.raised || fire else false) to fire
    }
}
