package dz.hirz.app.guardian

import dz.hirz.app.push.AlarmPolicy
import dz.hirz.app.push.Push
import dz.hirz.app.push.PushPayload
import dz.hirz.shared.api.DigestItem
import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.RoutineDto
import dz.hirz.shared.api.RoutineTemplate
import dz.hirz.shared.asset.AssetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RoutineLogicTest {
    private val school = RoutineDto("r1", "a1", "p1", days = setOf(1, 2, 3, 4, 7), arriveByMin = 480, dismissalMin = 990)

    @Test
    fun labelShowsTheWeekFromSundayAndTheTimes() {
        val en = RoutineLogic.label(school, "Ecole", "en")
        assertTrue(en.startsWith("Ecole"), en)
        assertTrue("Sun, Mon, Tue, Wed, Thu" in en, "the Algerian week starts on Sunday: $en")
        assertTrue("08:00" in en && "16:30" in en, en)
        listOf("ar", "fr").forEach { assertTrue("08:00" in RoutineLogic.label(school, "Ecole", it) && "null" !in RoutineLogic.label(school, "Ecole", it)) }
    }

    @Test
    fun templatesFitTheAsset() {
        assertTrue(RoutineTemplate.SCHOOL in RoutineLogic.templatesFor(AssetType.CHILD))
        assertFalse(RoutineTemplate.PASTURE_DAY in RoutineLogic.templatesFor(AssetType.CHILD))
        assertEquals(setOf(RoutineTemplate.PASTURE_DAY, RoutineTemplate.BARN_NIGHT), RoutineLogic.templatesFor(AssetType.LIVESTOCK).toSet())
        RoutineTemplate.entries.forEach { t -> listOf("ar", "fr", "en").forEach { assertTrue(RoutineLogic.templateName(t, it).isNotBlank(), "$t/$it") } }
    }

    @Test
    fun digestLineNeverNamesACondition() {
        val words = listOf("توحد", "autisme", "autism", "زهايمر", "démence", "dementia", "null")
        for (lang in listOf("ar", "fr", "en")) {
            val line = DigestLogic.line(DigestItem("a1", "Yanis", "PERSON_ALERT", 1, 0L), lang).lowercase()
            words.forEach { assertFalse(it in line, "$lang: $line") }
        }
        assertTrue("×3" in DigestLogic.line(DigestItem("a1", "Yanis", "UNUSUAL_STOP", 3, 0L), "en"))
        assertTrue("Yanis" in DigestLogic.line(DigestItem("a1", "Yanis", "UNUSUAL_STOP", 1, 0L), "ar"))
    }

    @Test
    fun routineReasonsReadInThreeLanguages() {
        val unknown = ReasonText.render(Reason("unknown_code"), "en")
        val args = mapOf("place" to "Ecole", "by" to "08:00", "minutes" to "15")
        for (code in listOf("missed_arrival", "stationary_after_dismissal", "unusual_stop", "pacing_loop", "new_place")) {
            for (lang in listOf("ar", "fr", "en")) {
                val text = ReasonText.render(Reason(code, args), lang)
                assertTrue(text.isNotBlank() && "null" !in text, "$lang/$code: $text")
                if (lang == "en") assertNotEquals(unknown, text, code)
            }
        }
        assertTrue("Ecole" in ReasonText.render(Reason("missed_arrival", args), "en"))
    }

    @Test
    fun quietIncidentDoesNotRingAndDigestIsANotice() {
        val quiet = assertIs<Push.Incident>(PushPayload.parse(mapOf("t" to "incident_quiet", "id" to "i-1", "tier" to "P1", "asset" to "Yanis")))
        assertTrue(quiet.quiet)
        assertFalse(AlarmPolicy.isAlarm(quiet), "verify first: the first push is quiet")
        assertEquals(Push.Notice("digest", "Famille"), PushPayload.parse(mapOf("t" to "digest", "asset" to "Famille")))
    }
}
