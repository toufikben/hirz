package dz.hirz.app.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushPayloadTest {
    private val sos = mapOf("t" to "incident", "id" to "i-1", "tier" to "P0", "asset" to "Ahmed", "rule" to "SOS")

    @Test
    fun parsesIncidentPayload() {
        val push = assertIs<Push.Incident>(PushPayload.parse(sos))
        assertEquals("i-1", push.incidentId)
        assertEquals("P0", push.tier)
        assertEquals("Ahmed", push.assetName)
    }

    @Test
    fun parsesCheckIn() {
        assertEquals(Push.CheckIn("Ahmed"), PushPayload.parse(mapOf("t" to "checkin", "asset" to "Ahmed")))
    }

    @Test
    fun ignoresUnknownOrIncompletePayloads() {
        assertNull(PushPayload.parse(mapOf("t" to "promo")))
        assertNull(PushPayload.parse(mapOf("t" to "incident", "tier" to "P0")))
        assertNull(PushPayload.parse(emptyMap()))
    }

    @Test
    fun p2NeverBuildsAlarm() {
        assertTrue(AlarmPolicy.isAlarm(PushPayload.parse(sos)!!))
        assertTrue(AlarmPolicy.isAlarm(PushPayload.parse(sos + ("tier" to "P1"))!!))
        assertFalse(AlarmPolicy.isAlarm(PushPayload.parse(sos + ("tier" to "P2"))!!))
        assertFalse(AlarmPolicy.isAlarm(Push.CheckIn("Ahmed")))
    }

    @Test
    fun notificationTextHasNoCoordinatesAndNoAlarmingWords() {
        for (lang in listOf("ar", "fr", "en")) {
            val (title, body) = AlarmText.of(PushPayload.parse(sos) as Push.Incident, lang)
            val text = "$title $body"
            assertTrue("Ahmed" in text, text)
            assertFalse(Regex("""\d+\.\d{3,}""").containsMatchIn(text), "no coordinates: $text")
            listOf("اختطاف", "kidnap", "enlèvement").forEach { assertFalse(it in text.lowercase(), "no '$it' wording: $text") }
        }
    }
}
