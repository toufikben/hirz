package dz.hirz.server.traccar

import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.signal.AlarmKind
import dz.hirz.shared.signal.FixSource
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.OffsetDateTime

private const val KNOTS_TO_KMH = 1.852

/** Traccar alarm names → the contract (design §3). Anything else is ignored, never guessed. */
private val ALARMS = mapOf(
    "sos" to AlarmKind.SOS,
    "powerCut" to AlarmKind.POWER_CUT,
    "tampering" to AlarmKind.TAMPER,
    "removing" to AlarmKind.REMOVAL,
    "jamming" to AlarmKind.JAMMING,
    "fallDown" to AlarmKind.FALL,
)

/**
 * Traccar position → the signal contract, with units converted once, here (design §3). A position Traccar marks
 * `outdated` (a GT06 heartbeat repeating the last location) or not `valid` is a heartbeat without a position, so the
 * map never jumps back to an old spot.
 */
object TraccarMapper {
    fun toSignal(p: TraccarPosition, eventAlarm: String? = null): IngestSignal {
        val a = p.attributes
        fun bool(key: String) = (a[key] as? JsonPrimitive)?.booleanOrNull
        fun num(key: String) = (a[key] as? JsonPrimitive)?.doubleOrNull
        val hasFix = p.valid && !p.outdated && p.fixTime != null
        val alarmText = listOfNotNull((a["alarm"] as? JsonPrimitive)?.content, eventAlarm).joinToString(",")
        return IngestSignal(
            clientId = "tc-${p.id}",
            fixTimeMs = if (hasFix) instantMs(p.fixTime!!) else null,
            position = if (hasFix) LatLon(p.latitude, p.longitude) else null,
            accuracyM = if (hasFix) p.accuracy.takeIf { it > 0 } else null,
            fixSource = if (hasFix) FixSource.GNSS else null,
            speedKmh = if (hasFix) p.speed * KNOTS_TO_KMH else null,
            heading = if (hasFix) p.course else null,
            battery = num("batteryLevel")?.toInt()?.coerceIn(0, 100),
            charging = bool("charge"),
            alarms = alarms(alarmText),
            heartbeat = true,
            // A supply voltage above zero proves external power; zero proves nothing (the power-cut alarm carries that).
            externalPower = num("power")?.takeIf { it > 0 }?.let { true },
            ignition = bool("ignition"),
            motion = bool("motion"),
        )
    }

    fun alarms(text: String): Set<AlarmKind> = text.split(',').mapNotNull { ALARMS[it.trim()] }.toSet()

    private fun instantMs(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
}
