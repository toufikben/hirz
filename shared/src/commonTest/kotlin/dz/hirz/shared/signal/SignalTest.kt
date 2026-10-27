package dz.hirz.shared.signal

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SignalTest {
    private fun heartbeatOnly() = Signal(
        sourceId = "src-1", assetId = "asset-1",
        fixTimeMs = null, serverTimeMs = 10_000,
        position = null, fixSource = null,
        speedKmh = null, heading = null,
        heartbeat = true,
        battery = 80, charging = false, externalPower = null,
        ignition = null, motion = null, tilt = null,
        alarms = emptySet(),
    )

    @Test
    fun ageIsMeasuredFromFixTime() {
        val signal = heartbeatOnly().copy(
            fixTimeMs = 1_000,
            position = Position(36.7538, 3.0588, 12.0, false),
            fixSource = FixSource.GNSS,
        )
        assertEquals(55_000L, signal.ageMs(56_000))
    }

    @Test
    fun ageIsNullWithoutFix() {
        assertNull(heartbeatOnly().ageMs(56_000))
    }

    @Test
    fun rejectsLatitudeOutOfRange() {
        assertFailsWith<IllegalArgumentException> { Position(91.0, 0.0, null, false) }
    }

    @Test
    fun rejectsLongitudeOutOfRange() {
        assertFailsWith<IllegalArgumentException> { Position(0.0, 181.0, null, false) }
    }

    @Test
    fun rejectsNegativeAccuracy() {
        assertFailsWith<IllegalArgumentException> { Position(0.0, 0.0, -1.0, false) }
    }

    @Test
    fun roundTripsThroughJson() {
        val signal = heartbeatOnly().copy(
            fixTimeMs = 1_000,
            position = Position(36.7538, 3.0588, 12.0, true),
            fixSource = FixSource.CELL,
            speedKmh = 42.5, heading = 90.0,
            ignition = true, motion = true, tilt = false,
            alarms = setOf(AlarmKind.SOS),
        )
        val json = Json.encodeToString(Signal.serializer(), signal)
        assertEquals(signal, Json.decodeFromString(Signal.serializer(), json))
    }
}
