package dz.hirz.app.guardian

import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.Reason
import dz.hirz.shared.rules.Freshness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GuardianLogicTest {
    @Test
    fun markerStyleCoversFreshness() {
        val fresh = markerStyle(Freshness.FRESH)
        val stale = markerStyle(Freshness.STALE)
        val silent = markerStyle(Freshness.SILENT)
        assertEquals(3, setOf(fresh, stale, silent).size)
        // a cue that is not colour: filled → hollow → question mark
        assertFalse(fresh.hollow)
        assertTrue(stale.hollow)
        assertEquals("?", silent.glyph)
    }

    @Test
    fun captionFormatsAgeAndAccuracy() {
        assertEquals("منذ 55 ث ±25 م", Captions.age(55_000, 25.0, "ar"))
        assertEquals("55 s ago ±25 m", Captions.age(55_000, 25.0, "en"))
        assertEquals("il y a 55 s ±25 m", Captions.age(55_000, 25.0, "fr"))
        assertEquals("3 min ago ±40 m", Captions.age(200_000, 40.4, "en"))
        assertEquals("No data", Captions.age(null, null, "en"))
        assertEquals("لا بيانات", Captions.age(null, 10.0, "ar"))
    }

    @Test
    fun reasonRenderingCoversAllP1Codes() {
        val codes = listOf(
            Reason("sos"), Reason("checkin_help"), Reason("duress"),
            Reason("left_place", mapOf("place" to "المدرسة", "readings" to "3", "distanceM" to "120", "accuracyM" to "12", "ageS" to "5")),
            Reason("entered_place", mapOf("place" to "Quarry", "readings" to "3", "distanceM" to "-10", "accuracyM" to "9", "ageS" to "2")),
            Reason("silent_dead", mapOf("minutes" to "6")), Reason("silent_alive", mapOf("minutes" to "11")),
            Reason("silence_during_incident", mapOf("mode" to "dead", "minutes" to "6")),
        )
        for (lang in listOf("ar", "fr", "en")) {
            val fallback = ReasonText.render(Reason("no_such_code"), lang)
            for (r in codes) {
                val text = ReasonText.render(r, lang)
                assertNotEquals(fallback, text, "${r.code} has no $lang text")
                r.args["place"]?.let { assertTrue(it in text, "$lang ${r.code} must name the place: $text") }
            }
        }
    }

    @Test
    fun polygonDraftNeedsThreeDistinctPoints() {
        val a = LatLon(36.75, 3.05)
        assertFalse(PolygonDraft(listOf(a, a, LatLon(36.76, 3.05))).isValid)
        assertTrue(PolygonDraft(listOf(a, LatLon(36.76, 3.05), LatLon(36.76, 3.06))).isValid)
    }

    @Test
    fun circleDraftEnforcesMargin50AndRadius30() {
        val c = LatLon(36.75, 3.05)
        assertFalse(CircleDraft(c, radiusM = 100.0, marginM = 40).isValid)
        assertFalse(CircleDraft(c, radiusM = 20.0, marginM = 50).isValid)
        assertTrue(CircleDraft(c, radiusM = 30.0, marginM = 50).isValid)
    }

    @Test
    fun accuracyRingIsTrueToScale() {
        val ring = accuracyRing(LatLon(36.75, 3.05), 100.0, points = 36)
        assertEquals(37, ring.size)
        assertEquals(ring.first(), ring.last())
        val north = ring.maxBy { it.lat }
        assertTrue(kotlin.math.abs((north.lat - 36.75) * 111_320 - 100) < 2)
    }
}
