package dz.hirz.server.places

import dz.hirz.server.support.TestDb
import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.ShapeDto
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeometryTest {
    /** Rectangle around Place des Martyrs, Algiers. */
    private val square = ShapeDto.Polygon(
        listOf(LatLon(36.7850, 3.0595), LatLon(36.7850, 3.0625), LatLon(36.7870, 3.0625), LatLon(36.7870, 3.0595)),
    )

    @Test
    fun wktUsesLonLatOrder() {
        assertEquals("POINT(3.0588 36.7538)", Geometry.toWkt(ShapeDto.Circle(LatLon(36.7538, 3.0588), 100.0)))
    }

    @Test
    fun polygonIsClosed() {
        val wkt = Geometry.toWkt(square)
        assertTrue(wkt.startsWith("POLYGON(("), wkt)
        val coords = wkt.removePrefix("POLYGON((").removeSuffix("))").split(", ")
        assertEquals(5, coords.size)
        assertEquals(coords.first(), coords.last())
        assertEquals("3.0595 36.785", coords.first())
    }

    @Test
    fun polygonNeedsThreeDistinctPoints() {
        assertFailsWith<IllegalArgumentException> {
            ShapeDto.Polygon(listOf(LatLon(1.0, 1.0), LatLon(1.0, 1.0), LatLon(2.0, 2.0)))
        }
    }

    @Test
    fun knownPointInsideAlgiersSquare() = TestDb.fresh().use { db ->
        db.connection.use { c ->
            assertTrue(Geometry.distanceOutsideM(c, square, LatLon(36.7860, 3.0610)) <= 0.0)
            val outside = Geometry.distanceOutsideM(c, square, LatLon(36.7888, 3.0610))
            assertTrue(outside in 150.0..250.0, "outside by $outside m")
        }
    }

    @Test
    fun insideDepthIsNegativeDistanceToEdge() = TestDb.fresh().use { db ->
        db.connection.use { c ->
            val depth = Geometry.distanceOutsideM(c, square, LatLon(36.7860, 3.0610))
            assertTrue(depth in -120.0..-100.0, "depth $depth m (≈111 m to the north/south edges)")
        }
    }

    @Test
    fun circleDistanceIsFromTheRim() = TestDb.fresh().use { db ->
        db.connection.use { c ->
            val circle = ShapeDto.Circle(LatLon(36.7538, 3.0588), 100.0)
            val outside = Geometry.distanceOutsideM(c, circle, LatLon(36.7538 + 0.0027, 3.0588)) // ≈300 m north
            assertTrue(outside in 190.0..210.0, "outside by $outside m")
            assertTrue(Geometry.distanceOutsideM(c, circle, LatLon(36.7538, 3.0588)) < -99.0)
        }
    }

    @Test
    fun activeWindowUsesAlgiersTime() {
        val sundayMorning = listOf(ActiveWindow(setOf(7), 7 * 60 + 30, 8 * 60))
        assertTrue(Geometry.isActive(sundayMorning, Instant.parse("2026-11-01T06:45:00Z")))  // 07:45 Algiers, Sunday
        assertFalse(Geometry.isActive(sundayMorning, Instant.parse("2026-11-01T07:15:00Z"))) // 08:15 Algiers
        assertFalse(Geometry.isActive(sundayMorning, Instant.parse("2026-11-02T06:45:00Z"))) // Monday
        assertTrue(Geometry.isActive(emptyList(), Instant.parse("2026-11-02T03:00:00Z")))    // no windows = always
    }
}
