package dz.hirz.app.guardian

import dz.hirz.shared.api.CloseReason
import dz.hirz.shared.api.EscalationViewDto
import dz.hirz.shared.api.PendingCloseDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncidentDetailLogicTest {
    private val names = mapOf("m1" to "أمينة", "m2" to "Karim")
    private val clock: (Long) -> String = { ms -> if (ms == 1_000L) "15:43:00" else "?" }

    @Test
    fun stageTextShowsOwnerAndDeadline() {
        assertEquals("بانتظار أمينة حتى 15:43:00", LadderText.render(EscalationViewDto("GUARDIANS", "m1", 1_000), names, "ar", clock))
        assertEquals("Waiting for Karim until 15:43:00", LadderText.render(EscalationViewDto("GUARDIANS", "m2", 1_000), names, "en", clock))
        assertEquals("En attente de Karim jusqu'à 15:43:00", LadderText.render(EscalationViewDto("GUARDIANS", "m2", 1_000), names, "fr", clock))
        assertEquals("Contacts alerted by SMS", LadderText.render(EscalationViewDto("CONTACTS", null, 1_000), names, "en", clock))
        assertEquals("Nobody answered — alerting everyone again", LadderText.render(EscalationViewDto("EVERYONE_UNAVAILABLE", null, 1_000), names, "en", clock))
        assertNull(LadderText.render(null, names, "en", clock))
        assertNull(LadderText.render(EscalationViewDto("CLAIMED", "m1", null), names, "en", clock), "a claimed incident shows its owner instead")
        assertEquals("بانتظار عضو حتى 15:43:00", LadderText.render(EscalationViewDto("GUARDIANS", "zz", 1_000), names, "ar", clock))
    }

    @Test
    fun foundSafeExplainsMissingProof() {
        val hint = CloseHint.forReason(CloseReason.FOUND_SAFE, tierP0 = false, editors = 2, lang = "en")
        assertTrue("home" in hint && "second guardian" in hint, hint)
        assertTrue("ثانٍ" in CloseHint.forReason(CloseReason.FAMILY, tierP0 = true, editors = 2, lang = "ar"))
        assertTrue("again" in CloseHint.forReason(CloseReason.FAMILY, tierP0 = true, editors = 1, lang = "en"), "the only guardian confirms with a second tap")
        assertEquals("", CloseHint.forReason(CloseReason.FAMILY, tierP0 = false, editors = 2, lang = "en"))

        val mine = PendingCloseDto("me", CloseReason.FAMILY, 0, 120_000)
        assertEquals(PendingAction.WAIT_FOR_OTHER, CloseHint.pending(mine, myMemberId = "me", editors = 2))
        assertEquals(PendingAction.CONFIRM, CloseHint.pending(mine, myMemberId = "me", editors = 1))
        assertEquals(PendingAction.CONFIRM, CloseHint.pending(mine, myMemberId = "other", editors = 2))
        assertEquals(PendingAction.NONE, CloseHint.pending(null, myMemberId = "me", editors = 2))
    }

    @Test
    fun postTextNeverContainsCoordinatesOrCodeWord() {
        for (lang in listOf("ar", "fr", "en")) {
            val text = PostText.compose(
                name = "Ahmed", clothes = "red jacket", lastSeenPlace = "near the school", lastSeenAt = "15:20", familyPhone = "0550123456", lang = lang,
            )
            assertTrue("Ahmed" in text && "red jacket" in text && "0550123456" in text && "15:20" in text, text)
            assertFalse(Regex("""\d{1,3}\.\d{4,}""").containsMatchIn(text), "no coordinates: $text")
        }
        val minimal = PostText.compose("Lina", null, null, null, "0550123456", "en")
        assertFalse("null" in minimal)
    }

    @Test
    fun reporterRunsOnlyWhileOpenAndVisible() = runTest {
        assertTrue(LocationReporter.shouldRun(open = true, visible = true))
        assertFalse(LocationReporter.shouldRun(open = false, visible = true))
        assertFalse(LocationReporter.shouldRun(open = true, visible = false))

        var open = true
        val sent = mutableListOf<Int>()
        var waits = 0
        LocationReporter.run(
            active = { open },
            send = { sent += sent.size },
            wait = { ms -> assertEquals(LocationReporter.INTERVAL_MS, ms); waits++; if (waits == 3) open = false },
        )
        assertEquals(3, sent.size, "one send per 30 s while open, none after close")
    }
}
