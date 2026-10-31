package dz.hirz.app.guardian

import dz.hirz.shared.api.Outcome
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrialLogicTest {
    @Test
    fun consentTextInThreeLanguages() {
        for (lang in listOf("ar", "fr", "en")) {
            val text = TrialLogic.consentText(lang)
            assertTrue(text.length > 200, "$lang consent is the whole text")
            assertFalse("null" in text)
        }
        assertTrue("leave" in TrialLogic.consentText("en").lowercase() && "location" in TrialLogic.consentText("en").lowercase())
    }

    @Test
    fun onlyRulesThatAlertAreOffered() {
        val rules = listOf(
            dz.hirz.shared.api.RuleSettingDto(dz.hirz.shared.api.RuleId.PLACE_EXIT, true, dz.hirz.shared.api.Tier.P1, dz.hirz.shared.api.Tier.P1),
            dz.hirz.shared.api.RuleSettingDto(dz.hirz.shared.api.RuleId.HAZARD_PROXIMITY, false, dz.hirz.shared.api.Tier.P1, dz.hirz.shared.api.Tier.P1),
        )
        assertEquals(listOf(dz.hirz.shared.api.RuleId.SOS, dz.hirz.shared.api.RuleId.PLACE_EXIT), TrialLogic.testableRules(rules))
    }

    @Test
    fun leaveNeedsConfirmation() {
        assertEquals(TrialLogic.LeaveStep.CONFIRM, TrialLogic.leaveTap(confirming = false))
        assertEquals(TrialLogic.LeaveStep.LEAVE, TrialLogic.leaveTap(confirming = true))
    }

    @Test
    fun outcomeChoicesMapToCodes() {
        assertEquals(Outcome.entries.toSet(), TrialLogic.OUTCOMES.toSet())
        TrialLogic.OUTCOMES.forEach { o -> listOf("ar", "fr", "en").forEach { assertTrue(TrialLogic.outcomeLabel(o, it).isNotBlank(), "$o/$it") } }
    }

    @Test
    fun reportCarriesNoLocation() {
        val json = Json.encodeToString(dz.hirz.shared.api.ProblemReportRequest.serializer(), TrialLogic.report("c1", "  it rang twice  ", "1.4.0", "Pixel 7"))
        assertTrue("\"text\":\"it rang twice\"" in json, json)
        listOf("lat", "lon", "position").forEach { assertFalse(it in json, json) }
    }
}
