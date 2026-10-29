package dz.hirz.server.traccar

import dz.hirz.shared.api.LatLon
import dz.hirz.shared.signal.AlarmKind
import dz.hirz.shared.signal.FixSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fixture(name: String): String = TraccarMapperTest::class.java.getResource("/traccar/$name")!!.readText()

class TraccarMapperTest {
    private fun position(name: String) = TraccarJson.positionForward(fixture(name))

    @Test
    fun gt06FixMapsToTheContract() {
        val f = position("gt06_fix.json")
        assertEquals("359339075123456", f.device.uniqueId)
        val s = TraccarMapper.toSignal(f.position)
        assertEquals("tc-7-${Instant.parse("2026-11-01T08:00:00Z").toEpochMilli()}", s.clientId, "Traccar forwards before storing: id is 0 there")
        assertEquals(LatLon(36.7538, 3.0588), s.position)
        assertEquals(Instant.parse("2026-11-01T08:00:00Z").toEpochMilli(), s.fixTimeMs)
        assertEquals(18.52, s.speedKmh!!, 1e-9)
        assertEquals(90.0, s.heading)
        assertEquals(FixSource.GNSS, s.fixSource)
        assertNull(s.accuracyM, "accuracy 0 means unknown: Core estimates and flags it")
        assertEquals(80, s.battery)
        assertEquals(false, s.charging)
        assertEquals(false, s.ignition)
        assertEquals(true, s.motion)
        assertTrue(s.alarms.isEmpty())
        assertTrue(s.heartbeat)
    }

    @Test
    fun outdatedPositionIsHeartbeatOnly() {
        val s = TraccarMapper.toSignal(position("gt06_heartbeat_outdated.json").position)
        assertNull(s.position, "never a jump back to the last known spot")
        assertNull(s.fixTimeMs)
        assertTrue(s.heartbeat)
        assertEquals(64, s.battery)
        assertEquals(true, s.charging)
    }

    @Test
    fun invalidFixIsHeartbeatOnly() {
        val s = TraccarMapper.toSignal(position("invalid_fix.json").position)
        assertNull(s.position)
        assertNull(s.fixTimeMs)
        assertEquals(100, s.battery, "out-of-range battery is clamped")
        assertEquals(true, s.externalPower)
    }

    @Test
    fun alarmsMapOneToOne() {
        assertEquals(setOf(AlarmKind.SOS, AlarmKind.POWER_CUT), TraccarMapper.toSignal(position("gt06_sos.json").position).alarms)
        assertEquals(12.0, TraccarMapper.toSignal(position("gt06_sos.json").position).accuracyM)
        assertTrue(TraccarMapper.toSignal(position("invalid_fix.json").position).alarms.isEmpty(), "vibration is not a contract alarm")
        assertEquals(setOf(AlarmKind.TAMPER, AlarmKind.REMOVAL, AlarmKind.JAMMING, AlarmKind.FALL), TraccarMapper.alarms("tampering, removing,jamming,fallDown,overspeed"))
    }

    @Test
    fun h02PowerCutFixKeepsExternalPowerUnknown() {
        val s = TraccarMapper.toSignal(position("h02_fix_power_cut.json").position)
        assertNull(s.externalPower, "0 V says nothing reliable; the alarm carries the fact")
        assertEquals(setOf(AlarmKind.POWER_CUT), s.alarms)
        assertEquals(false, s.ignition)
    }

    @Test
    fun eventAlarmKeysMatchThePositionForward() {
        val e = TraccarJson.eventForward(fixture("event_alarm_sos.json"))
        assertEquals("alarm", e.event.type)
        val s = TraccarMapper.toSignal(e.position!!, e.event.attributes["alarm"]?.toString()?.trim('"'))
        val fix = TraccarMapper.toSignal(e.position!!)
        assertEquals(fix.clientId + "-sos", s.clientId, "the alarm never deduplicates against the plain fix of the same second")
        assertEquals(setOf(AlarmKind.SOS), s.alarms)
        assertEquals(s.clientId, TraccarMapper.toSignal(e.position!!.copy(attributes = kotlinx.serialization.json.buildJsonObject { put("alarm", kotlinx.serialization.json.JsonPrimitive("sos")) })).clientId,
            "the position forward and the event forward of one alarm share a key")
    }
}
