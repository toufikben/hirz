package dz.hirz.app.companion

import dz.hirz.app.push.Push
import dz.hirz.app.push.PushPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteCommandsTest {
    @Test
    fun pushParsesDeviceCommands() {
        assertEquals(Push.Ring("Ahmed"), PushPayload.parse(mapOf("t" to "ring", "asset" to "Ahmed")))
        assertEquals(Push.Rate(high = true), PushPayload.parse(mapOf("t" to "high_rate", "asset" to "Ahmed")))
        assertEquals(Push.Rate(high = false), PushPayload.parse(mapOf("t" to "normal_rate", "asset" to "Ahmed")))
        assertEquals(Push.SmsPending, PushPayload.parse(mapOf("t" to "sms_pending", "asset" to "")))
        assertEquals(Push.CloseConfirm("i-1", "Ahmed"), PushPayload.parse(mapOf("t" to "close_confirm", "id" to "i-1", "asset" to "Ahmed")))
        assertEquals(Push.Notice("custody_changed", "Ahmed"), PushPayload.parse(mapOf("t" to "custody_changed", "asset" to "Ahmed", "tier" to "P2")))
        assertNull(PushPayload.parse(mapOf("t" to "close_confirm", "asset" to "Ahmed")), "a confirm without an incident id is ignored")
    }

    @Test
    fun ringStopsAfter30s() {
        val ring = RingSession(startedMs = 1_000)
        assertFalse(ring.shouldStop(30_999))
        assertTrue(ring.shouldStop(31_000))
        val stopped = RingSession(startedMs = 1_000)
        stopped.stop()
        assertTrue(stopped.shouldStop(1_001), "the stop button ends it at once")
    }

    @Test
    fun notificationSosNeedsConfirm() {
        val sos = SosConfirm(windowMs = 10_000)
        assertEquals(SosConfirm.Step.ARMED, sos.tap(0), "the first tap only asks for confirmation")
        assertEquals(SosConfirm.Step.SEND, sos.tap(4_000))
        assertEquals(SosConfirm.Step.ARMED, sos.tap(5_000), "after sending, a new SOS needs a new confirmation")
        assertEquals(SosConfirm.Step.ARMED, sos.tap(20_000), "a confirmation after the window starts over")
        assertEquals(SosConfirm.Step.SEND, sos.tap(29_999))
    }

    @Test
    fun cadenceFollowsRateCommands() {
        val c = CompanionCadence()
        assertEquals(60_000, c.intervalMs(0))
        c.serverSaysHighRate(true, 0)
        assertEquals(15_000, c.intervalMs(1))
        c.serverSaysHighRate(false, 2)
        assertEquals(60_000, c.intervalMs(3))
        c.sosPressed(10)
        c.serverSaysHighRate(false, 11)
        assertEquals(15_000, c.intervalMs(12), "normal_rate never cuts short the 10 minutes after an SOS")
    }
}
