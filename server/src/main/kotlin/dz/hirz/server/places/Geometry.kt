package dz.hirz.server.places

import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.ShapeDto
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId

/**
 * The one place where shapes become PostGIS geometry. WKT is `lon lat` (x y); the API is always named
 * lat/lon — the v2 lab measured what a swapped order does (a "zone" in the desert east of Algeria).
 */
object Geometry {
    val ALGIERS: ZoneId = ZoneId.of("Africa/Algiers")

    private fun LatLon.wkt() = "$lon $lat"

    /** Circles are stored as their centre point (the radius lives in the shape); polygons as a closed ring. */
    fun toWkt(shape: ShapeDto): String = when (shape) {
        is ShapeDto.Circle -> "POINT(${shape.center.wkt()})"
        is ShapeDto.Polygon -> {
            val ring = shape.points.let { if (it.first() == it.last()) it else it + it.first() }
            "POLYGON((${ring.joinToString(", ") { it.wkt() }}))"
        }
    }

    /** Metres outside [shape]'s edge: positive outside, zero on the edge, negative inside (depth to the edge). */
    fun distanceOutsideM(c: Connection, shape: ShapeDto, point: LatLon): Double {
        val sql = when (shape) {
            is ShapeDto.Circle -> "SELECT ST_Distance(ST_GeogFromText(?), ST_GeogFromText(?)) - ${shape.radiusM}"
            is ShapeDto.Polygon ->
                """SELECT CASE WHEN ST_Covers(g, p) THEN -ST_Distance(ST_Boundary(g::geometry)::geography, p)
                               ELSE ST_Distance(g, p) END
                   FROM (SELECT ST_GeogFromText(?) AS g, ST_GeogFromText(?) AS p) x"""
        }
        return c.prepareStatement(sql).apply {
            setString(1, "SRID=4326;" + toWkt(shape))
            setString(2, "SRID=4326;POINT(${point.wkt()})")
        }.executeQuery().use { it.next(); it.getDouble(1) }
    }

    /** True when no window is set, or [at] in Algiers local time falls inside one. */
    fun isActive(windows: List<ActiveWindow>, at: Instant): Boolean {
        if (windows.isEmpty()) return true
        val local = at.atZone(ALGIERS)
        val minute = local.hour * 60 + local.minute
        return windows.any { local.dayOfWeek.value in it.daysOfWeek && minute >= it.startMinute && minute < it.endMinute }
    }
}
