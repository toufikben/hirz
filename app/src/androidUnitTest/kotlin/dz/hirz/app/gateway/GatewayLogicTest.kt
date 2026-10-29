package dz.hirz.app.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayLogicTest {
    @Test
    fun outboxResultMapping() {
        // Android: Activity.RESULT_OK = -1; SmsManager.RESULT_ERROR_GENERIC_FAILURE = 1, RADIO_OFF = 2, NULL_PDU = 3, NO_SERVICE = 4.
        assertEquals(SmsOutcome("sent", null), SmsOutcome.of(listOf(-1, -1)))
        assertEquals(SmsOutcome("failed", "radio_off"), SmsOutcome.of(listOf(-1, 2)), "one failed part fails the message")
        assertEquals(SmsOutcome("failed", "no_service"), SmsOutcome.of(listOf(4)))
        assertEquals(SmsOutcome("failed", "generic_failure"), SmsOutcome.of(listOf(1)))
        assertEquals(SmsOutcome("failed", "error_99"), SmsOutcome.of(listOf(99)))
        assertEquals(SmsOutcome("failed", "no_parts"), SmsOutcome.of(emptyList()))
    }

    @Test
    fun inboxForwardsAllMessages() {
        val parts = listOf(
            InboundPart("+213550123456", "1", 1_000),
            InboundPart("+213661000000", "promo: win a phone", 1_100),
            InboundPart("+213550123456", " and more", 1_000),
        )
        val messages = GatewayInbox.assemble(parts)
        assertEquals(2, messages.size, "every sender is forwarded; the server decides what is relevant")
        assertEquals("1 and more", messages.single { it.from == "+213550123456" }.body)
        assertEquals("promo: win a phone", messages.single { it.from == "+213661000000" }.body)
    }

    @Test
    fun gatewayHeartbeatEvery5Min() {
        assertEquals(300_000, GatewaySchedule.HEARTBEAT_MS)
        assertTrue(GatewaySchedule.heartbeatDue(lastMs = null, nowMs = 0))
        assertFalse(GatewaySchedule.heartbeatDue(lastMs = 0, nowMs = 299_999))
        assertTrue(GatewaySchedule.heartbeatDue(lastMs = 0, nowMs = 300_000))
        assertEquals(60_000, GatewaySchedule.POLL_MS)
    }
}
