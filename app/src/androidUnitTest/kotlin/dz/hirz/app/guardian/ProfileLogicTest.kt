package dz.hirz.app.guardian

import dz.hirz.app.companion.SimpleMode
import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.RuleSettingDto
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.asset.Condition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileLogicTest {
    @Test
    fun p0RowsShowNoSwitchOff() {
        val locked = RuleSettingDto(RuleId.HAZARD_PROXIMITY, true, Tier.P0, Tier.P0, locked = true)
        val open = RuleSettingDto(RuleId.PLACE_EXIT, true, Tier.P1, Tier.P1)
        assertFalse(ProfileLogic.showSwitch(locked))
        assertTrue(ProfileLogic.showSwitch(open))
        assertEquals(listOf(Tier.P0), ProfileLogic.tierChoices(locked, isOwner = false), "a guardian can only keep or raise")
        assertEquals(listOf(Tier.P0, Tier.P1, Tier.P2), ProfileLogic.tierChoices(open, isOwner = true))
        assertEquals(listOf(Tier.P0, Tier.P1), ProfileLogic.tierChoices(open, isOwner = false))
        assertEquals(22 * 60 + 30, ProfileLogic.parseClock("22:30"))
        assertNull(ProfileLogic.parseClock("25:00"))
    }

    @Test
    fun conditionLabelOnlyInProfileScreen() {
        val words = listOf("توحد", "autisme", "autism", "زهايمر", "démence", "dementia")
        val codes = listOf("night_exit", "night_exit_beyond", "hazard_entered", "running_away", "heading_past_address", "curfew", "coastal_night")
        for (lang in listOf("ar", "fr", "en")) {
            for (code in codes) {
                val text = ReasonText.render(Reason(code, mapOf("place" to "X", "distanceM" to "1", "speedKmh" to "1", "curfew" to "22:00")), lang).lowercase()
                words.forEach { assertFalse(it in text, "$lang/$code mentions a condition") }
            }
            assertTrue(ProfileLogic.conditionLabel(Condition.AUTISM, lang).isNotBlank())
        }
    }

    @Test
    fun everyNewReasonReadsInThreeLanguages() {
        val unknown = ReasonText.render(Reason("unknown_code"), "en")
        val codes = listOf("night_exit", "night_exit_beyond", "hazard_entered", "running_away", "heading_past_address", "curfew", "coastal_night")
        for (lang in listOf("ar", "fr", "en")) for (code in codes) {
            val text = ReasonText.render(Reason(code, mapOf("place" to "Bab", "distanceM" to "120", "speedKmh" to "11", "curfew" to "22:00",
                "readings" to "2", "accuracyM" to "8", "ageS" to "5")), lang)
            assertTrue(text.isNotBlank() && "null" !in text, "$lang/$code: $text")
            if (lang == "en") assertNotEquals(unknown, text, code)
        }
        RuleId.entries.forEach { r -> listOf("ar", "fr", "en").forEach { assertTrue(ProfileLogic.ruleName(r, it).isNotBlank(), "$r/$it") } }
    }

    @Test
    fun simpleModeHasOnlySosAndFine() {
        assertTrue(SimpleMode.isSimple(AssetType.ELDER))
        assertFalse(SimpleMode.isSimple(AssetType.CHILD))
        assertFalse(SimpleMode.isSimple(null))
        assertEquals(listOf(SimpleMode.Button.SOS, SimpleMode.Button.FINE), SimpleMode.buttons)
        listOf("ar", "fr").forEach { assertTrue(SimpleMode.spokenAfterSos(it).isNotBlank()) }
    }
}
