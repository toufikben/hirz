package dz.hirz.app.guardian

import dz.hirz.shared.api.Reason
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.api.VehicleStateDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VehicleLogicTest {
    private val now = 1_800_000_000_000L

    @Test
    fun modeLineSaysWhyAndSince() {
        val armed = VehicleStateDto(VehicleMode.ARMED, "auto_arm", sinceMs = now - 14 * 60_000)
        assertEquals("Armed — automatically after parking, 14 min ago", VehicleLogic.modeLine(armed, now, "en"))
        assertTrue("مسلّحة" in VehicleLogic.modeLine(armed, now, "ar") && "14" in VehicleLogic.modeLine(armed, now, "ar"))
        val driving = VehicleStateDto(VehicleMode.AUTHORIZED, "authorized", sinceMs = now - 5 * 60_000)
        assertTrue("autorisée" in VehicleLogic.modeLine(driving, now, "fr"))
        assertEquals("Disarmed", VehicleLogic.modeLine(VehicleStateDto(VehicleMode.DISARMED, "default"), now, "en"))
    }

    @Test
    fun engineStopOnlyOfferedToOwnerDuringTheft() {
        val theft = setOf(RuleId.ARMED_MOVEMENT)
        assertTrue(VehicleLogic.engineStopOffered(isOwner = true, openRules = theft, missing = false, engineCut = true))
        assertTrue(VehicleLogic.engineStopOffered(isOwner = true, openRules = emptySet(), missing = true, engineCut = true))
        assertTrue(VehicleLogic.engineStopOffered(isOwner = true, openRules = setOf(RuleId.TOWING), missing = false, engineCut = true))
        assertFalse(VehicleLogic.engineStopOffered(isOwner = false, openRules = theft, missing = false, engineCut = true))
        assertFalse(VehicleLogic.engineStopOffered(isOwner = true, openRules = setOf(RuleId.POWER_CUT), missing = false, engineCut = true))
        assertFalse(VehicleLogic.engineStopOffered(isOwner = true, openRules = theft, missing = false, engineCut = false))
        assertTrue(VehicleLogic.confirmMatches("أوقف", "ar") && VehicleLogic.confirmMatches(" STOP ", "en") && VehicleLogic.confirmMatches("arrêter", "fr"))
        assertFalse(VehicleLogic.confirmMatches("ok", "en"))
    }

    @Test
    fun refusalReasonsAreReadable() {
        val codes = listOf("not_owner", "no_theft_incident", "not_supported", "device_offline", "stale_fix", "too_fast", "queued_withdrawn", "traccar_unavailable")
        val fallback = VehicleLogic.refusal("something_new", "en")
        for (lang in listOf("ar", "fr", "en")) for (code in codes) {
            val text = VehicleLogic.refusal(code, lang)
            assertTrue(text.isNotBlank() && code !in text, "$lang/$code")
            if (lang == "en") assertNotEquals(fallback, text, code)
        }
        val vehicleReasons = listOf("armed_moved", "armed_ignition_on", "towed", "power_cut", "jamming", "jamming_alarm", "parked_tilt", "door_open_armed", "antenna_cut")
        val unknown = ReasonText.render(Reason("unknown_code"), "en")
        for (lang in listOf("ar", "fr", "en")) for (code in vehicleReasons) {
            val text = ReasonText.render(Reason(code, mapOf("distanceM" to "120", "speedKmh" to "30", "silentS" to "200")), lang)
            assertTrue(text.isNotBlank() && "null" !in text, "$lang/$code: $text")
            if (lang == "en") assertNotEquals(unknown, text, code)
        }
    }
}
