package dz.hirz.server.rules

import dz.hirz.shared.api.BusStopDto
import dz.hirz.shared.api.LatLon
import java.time.Duration
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sqrt

/** One good fix of a bus (P9). */
data class BusFix(val at: Instant, val p: LatLon, val accuracyM: Double, val speedKmh: Double? = null)

/**
 * The bus rules (design §4.3 `ROUTE_DEVIATION`, §12.1 route mode), pure. Distances use a local flat projection, which
 * is exact enough over a town.
 */
object BusRules {
    private const val M_PER_DEG = 111_320.0

    private fun xy(p: LatLon, lat0: Double) = (p.lon * M_PER_DEG * cos(Math.toRadians(lat0))) to (p.lat * M_PER_DEG)

    fun metres(a: LatLon, b: LatLon): Double {
        val (ax, ay) = xy(a, a.lat); val (bx, by) = xy(b, a.lat)
        return sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
    }

    /** The shortest distance from [p] to the polyline [path], in metres. */
    fun distanceToPathM(p: LatLon, path: List<LatLon>): Double {
        if (path.size == 1) return metres(p, path[0])
        val (px, py) = xy(p, p.lat)
        return path.zipWithNext().minOf { (a, b) ->
            val (ax, ay) = xy(a, p.lat); val (bx, by) = xy(b, p.lat)
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx; val cy = ay + t * dy
            sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
        }
    }

    /** The trailing fixes that satisfy [ok], newest last. */
    private fun suffix(fixes: List<BusFix>, ok: (BusFix) -> Boolean): List<BusFix> = fixes.takeLastWhile(ok)

    private fun spans(run: List<BusFix>, minMs: Long) = run.size >= 2 && Duration.between(run.first().at, run.last().at).toMillis() >= minMs

    /**
     * `ROUTE_DEVIATION`: the latest fixes are all clearly outside the corridor (beyond half its width plus their
     * accuracy) for at least [minMs]. GPS jitter on the corridor never adds up to that.
     */
    fun offCorridor(fixes: List<BusFix>, path: List<LatLon>, widthM: Double, minMs: Long = 60_000): Boolean =
        spans(suffix(fixes) { distanceToPathM(it.p, path) > widthM / 2 + it.accuracyM }, minMs)

    /** How far off the corridor the latest fix is, for the reason. */
    fun offBy(fixes: List<BusFix>, path: List<LatLon>, widthM: Double): Int =
        fixes.lastOrNull()?.let { (distanceToPathM(it.p, path) - widthM / 2).coerceAtLeast(0.0).toInt() } ?: 0

    /**
     * `UNSCHEDULED_STOP`: still within [radiusM] of one point for at least [minMs], and that point is not at a stop. A
     * red light or a queue is shorter than that.
     */
    fun stillOffStops(fixes: List<BusFix>, stops: List<BusStopDto>, radiusM: Double = 30.0, minMs: Long = 5 * 60_000): LatLon? {
        val last = fixes.lastOrNull() ?: return null
        val run = suffix(fixes) { metres(it.p, last.p) <= radiusM }
        if (!spans(run, minMs)) return null
        val centre = LatLon(run.map { it.p.lat }.average(), run.map { it.p.lon }.average())
        return centre.takeIf { stops.none { s -> metres(centre, s.at) <= s.radiusM + radiusM } }
    }

    /** `OVERSPEED_IN_SERVICE`: every latest fix at [kmh] or more for at least [minMs]. */
    fun overspeed(fixes: List<BusFix>, kmh: Double = 90.0, minMs: Long = 60_000): Boolean =
        spans(suffix(fixes) { (it.speedKmh ?: 0.0) >= kmh }, minMs)

    /** `STOP_LATE`: the first stop not reached yet whose due time plus [graceMin] has passed, if any. */
    fun lateStop(stops: List<BusStopDto>, reached: Set<Int>, minuteNow: Int, graceMin: Int = 10): BusStopDto? =
        stops.sortedBy { it.seq }.firstOrNull { it.seq !in reached }?.takeIf { minuteNow >= it.dueMin + graceMin }
}
