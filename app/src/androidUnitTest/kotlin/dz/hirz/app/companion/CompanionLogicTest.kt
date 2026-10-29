package dz.hirz.app.companion

import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.signal.AlarmKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompanionLogicTest {
    private fun fix(i: Int) = IngestSignal(clientId = "f$i", fixTimeMs = i.toLong())
    private fun sos(i: Int) = IngestSignal(clientId = "sos$i", alarms = setOf(AlarmKind.SOS))

    @Test
    fun queueNeverDropsAlarms() {
        val q = SignalQueue(capacity = 2_000)
        repeat(2_100) { i -> q.add(if (i % 420 == 0) sos(i) else fix(i)) }
        assertEquals(2_000, q.size)
        assertEquals(5, q.snapshot().count { AlarmKind.SOS in it.alarms })
        // the oldest ordinary fixes were the ones dropped
        assertTrue(q.snapshot().none { it.clientId == "f1" })
    }

    @Test
    fun queueBatchesAtMost100() {
        val q = SignalQueue()
        repeat(250) { q.add(fix(it)) }
        assertEquals(100, q.nextBatch().size)
        q.acknowledge(q.nextBatch().map { it.clientId }.toSet())
        assertEquals(150, q.size)
    }

    @Test
    fun sosJumpsTheQueue() {
        val q = SignalQueue()
        repeat(150) { q.add(fix(it)) }
        q.add(sos(999))
        assertEquals("sos999", q.nextBatch().first().clientId)
    }

    @Test
    fun duressAndNormalPinLookIdenticalToCarrier() {
        val salt = "s4lt"
        val normal = PinPad.submit("1234", salt)
        val duress = PinPad.submit("9876", salt)
        val wrong = PinPad.submit("5555", salt)
        assertEquals(normal.uiState, duress.uiState)
        assertEquals(normal.uiState, wrong.uiState)
        assertTrue(normal.signal.pinHash != duress.signal.pinHash)
        assertTrue(normal.signal.alarms.isEmpty() && duress.signal.alarms.isEmpty(), "the device never decides duress")
    }

    @Test
    fun cadenceIsHighAfterSosFor10Min() {
        val c = CompanionCadence()
        assertEquals(60_000L, c.intervalMs(nowMs = 0))
        c.sosPressed(nowMs = 1_000)
        assertEquals(15_000L, c.intervalMs(nowMs = 1_000 + 9 * 60_000))
        assertEquals(60_000L, c.intervalMs(nowMs = 1_000 + 10 * 60_000 + 1))
        c.serverSaysHighRate(true, nowMs = 2_000_000)
        assertEquals(15_000L, c.intervalMs(nowMs = 2_000_001))
        c.serverSaysHighRate(false, nowMs = 2_000_002)
        assertEquals(60_000L, c.intervalMs(nowMs = 2_000_003))
    }

    @Test
    fun heartbeatSentWithoutFix() {
        val s = CompanionSignals.heartbeat(battery = 64, charging = false, nowMs = 5_000)
        assertEquals(null, s.position)
        assertEquals(null, s.fixTimeMs)
        assertTrue(s.heartbeat)
        assertEquals(64, s.battery)
    }
}
