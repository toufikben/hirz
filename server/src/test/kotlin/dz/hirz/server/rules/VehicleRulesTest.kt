package dz.hirz.server.rules

import dz.hirz.shared.api.LatLon
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.Tier
import dz.hirz.shared.api.VehicleMode
import dz.hirz.shared.rules.RuleConfig
import dz.hirz.shared.signal.AlarmKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PARKED = LatLon(36.7538, 3.0588)
private fun north(m: Double) = LatLon(PARKED.lat + m / 111_320.0, PARKED.lon)
private val cfg = RuleConfig.DEFAULT

private fun reading(m: Double? = null, acc: Double? = 10.0, speed: Double? = 0.0, ignition: Boolean? = null, alarms: Set<AlarmKind> = emptySet(), fixMs: Long = 1_000) =
    VehicleReading(fixMs = m?.let { fixMs }, position = m?.let { north(it) }, accuracyM = acc, speedKmh = speed, ignition = ignition, alarms = alarms, isLatestFix = true)

private fun run(mode: VehicleMode, vararg rs: VehicleReading): List<VehicleHit> {
    var st = VehicleRuleState()
    return rs.flatMap { r -> VehicleRules.evaluate(st, mode, PARKED, r, cfg).also { st = it.first }.second }
}

class VehicleRulesTest {
    @Test
    fun armedIgnitionOnIsP1() {
        val hits = run(VehicleMode.ARMED, reading(ignition = true))
        assertEquals(listOf(RuleId.ARMED_MOVEMENT to Tier.P1), hits.map { it.rule to it.tier })
        assertEquals("armed_ignition_on", hits.single().reason.code)
    }

    @Test
    fun armedMove60mIsP1() {
        val hit = run(VehicleMode.ARMED, reading(m = 60.0, acc = 5.0)).single()
        assertEquals(RuleId.ARMED_MOVEMENT to Tier.P1, hit.rule to hit.tier)
        assertEquals("armed_moved", hit.reason.code)
    }

    @Test
    fun parkedDriftDoesNotAlarm() {
        val drift = listOf(30.0 to 40.0, 80.0 to 45.0, 55.0 to 60.0, 70.0 to 50.0)
        val hits = run(VehicleMode.ARMED, *drift.mapIndexed { i, (m, acc) -> reading(m = m, acc = acc, fixMs = 1_000L + i * 30_000) }.toTypedArray())
        assertTrue(hits.isEmpty(), hits.toString())
    }

    @Test
    fun armedFastIsP0() {
        val hits = run(VehicleMode.ARMED, reading(m = 20.0, speed = 25.0, ignition = true, fixMs = 1_000), reading(m = 200.0, speed = 30.0, ignition = true, fixMs = 31_000))
        assertTrue(hits.any { it.rule == RuleId.ARMED_MOVEMENT && it.tier == Tier.P0 }, hits.toString())
        assertTrue(run(VehicleMode.ARMED, reading(m = 600.0, acc = 10.0)).any { it.tier == Tier.P0 }, "500 m away is P0 at any speed")
    }

    @Test
    fun towingNeedsIgnitionOff() {
        val towed = run(VehicleMode.ARMED, reading(m = 70.0, acc = 5.0, ignition = false))
        assertEquals(listOf(RuleId.TOWING), towed.map { it.rule })
        val driven = run(VehicleMode.ARMED, reading(m = 70.0, acc = 5.0, ignition = true))
        assertTrue(driven.all { it.rule == RuleId.ARMED_MOVEMENT })
        val shaken = run(VehicleMode.ARMED, reading(ignition = false, alarms = setOf(AlarmKind.VIBRATION)))
        assertEquals(listOf(RuleId.PARKED_TILT), shaken.map { it.rule }, "shock without movement is tilt, not towing")
    }

    @Test
    fun powerCutP1WhenArmedP2Otherwise() {
        assertEquals(Tier.P1, run(VehicleMode.ARMED, reading(alarms = setOf(AlarmKind.POWER_CUT))).single { it.rule == RuleId.POWER_CUT }.tier)
        assertEquals(Tier.P2, run(VehicleMode.AUTHORIZED, reading(alarms = setOf(AlarmKind.POWER_CUT))).single().tier)
        assertEquals(Tier.P2, run(VehicleMode.DISARMED, reading(alarms = setOf(AlarmKind.ANTENNA_CUT))).single { it.rule == RuleId.ANTENNA_CUT }.tier)
    }

    @Test
    fun jammingAlarmP1() {
        assertEquals(RuleId.JAMMING to Tier.P1, run(VehicleMode.ARMED, reading(alarms = setOf(AlarmKind.JAMMING))).single().let { it.rule to it.tier })
    }

    @Test
    fun garageWithIgnitionOnWhileAuthorizedIsNotJamming() {
        val now = Instant.parse("2026-11-01T08:10:00Z")
        assertNull(VehicleRules.jamming(lastReportAt = now.minusSeconds(30), ignitionOn = true, alreadyFlagged = false, mode = VehicleMode.AUTHORIZED, now = now, cfg = cfg),
            "heartbeats still arrive: a garage is not jamming")
        val silent = assertNotNull(VehicleRules.jamming(now.minusSeconds(200), ignitionOn = true, alreadyFlagged = false, mode = VehicleMode.ARMED, now = now, cfg = cfg))
        assertEquals(RuleId.JAMMING to Tier.P1, silent.rule to silent.tier)
        assertEquals(Tier.P2, VehicleRules.jamming(now.minusSeconds(200), true, false, VehicleMode.AUTHORIZED, now, cfg)!!.tier)
        assertNull(VehicleRules.jamming(now.minusSeconds(200), ignitionOn = false, alreadyFlagged = false, mode = VehicleMode.ARMED, now = now, cfg = cfg),
            "a parked car going quiet is the silence rule's job")
        assertNull(VehicleRules.jamming(now.minusSeconds(200), true, alreadyFlagged = true, mode = VehicleMode.ARMED, now = now, cfg = cfg), "once per silence")
    }

    @Test
    fun disarmedRaisesNothingButPowerCutP2() {
        val hits = run(VehicleMode.DISARMED, reading(m = 900.0, speed = 80.0, ignition = true), reading(ignition = false, alarms = setOf(AlarmKind.VIBRATION, AlarmKind.DOOR)))
        assertTrue(hits.isEmpty(), hits.toString())
        assertTrue(run(VehicleMode.MAINTENANCE, reading(m = 900.0, speed = 80.0, ignition = true)).isEmpty())
    }

    @Test
    fun doorAndTiltOnlyWhenArmed() {
        assertEquals(setOf(RuleId.DOOR_OPEN_ARMED, RuleId.PARKED_TILT), run(VehicleMode.ARMED, reading(alarms = setOf(AlarmKind.DOOR, AlarmKind.VIBRATION))).map { it.rule }.toSet())
        assertTrue(run(VehicleMode.AUTHORIZED, reading(alarms = setOf(AlarmKind.DOOR, AlarmKind.VIBRATION))).isEmpty())
    }
}
