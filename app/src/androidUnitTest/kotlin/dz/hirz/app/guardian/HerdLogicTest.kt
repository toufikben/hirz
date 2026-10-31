package dz.hirz.app.guardian

import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HerdLogicTest {
    @Test
    fun livestockAndPresenceReasonsReadInThreeLanguages() {
        val unknown = ReasonText.render(Reason("unknown_code"), "en")
        val codes = listOf("herd_night_move", "herd_dispersed", "animal_still", "collar_cut", "truck_speed", "leash_break", "keyless_no_owner")
        val args = mapOf("herd" to "Ouled Djellal", "out" to "3", "heads" to "12", "spreadM" to "420", "hours" to "6",
            "speedKmh" to "40", "distanceM" to "80", "seconds" to "70")
        for (lang in listOf("ar", "fr", "en")) for (code in codes) {
            val text = ReasonText.render(Reason(code, args), lang)
            assertTrue(text.isNotBlank() && "null" !in text, "$lang/$code: $text")
            if (lang == "en") assertNotEquals(unknown, text, code)
        }
    }

    @Test
    fun lateReportsSayHowLate() {
        assertTrue("reported 300 min late" in ReasonText.render(Reason("collar_cut", mapOf("lateMin" to "300")), "en"))
        assertTrue("300" in ReasonText.render(Reason("collar_cut", mapOf("lateMin" to "300")), "ar"))
    }

    @Test
    fun marketDayIsOneWeeklyWindow() {
        assertEquals(listOf(ActiveWindow(setOf(2), 5 * 60, 14 * 60)), HerdLogic.marketWindow(2, "05:00", "14:00"))
        assertNull(HerdLogic.marketWindow(2, "14:00", "05:00"), "a market ends after it starts")
        assertNull(HerdLogic.marketWindow(8, "05:00", "14:00"))
        assertEquals("Tue 05:00–14:00", HerdLogic.marketLabel(ActiveWindow(setOf(2), 5 * 60, 14 * 60), "en"))
    }
}
