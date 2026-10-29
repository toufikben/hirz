package dz.hirz.app.companion

import dz.hirz.app.api.hirzJson
import dz.hirz.shared.api.CheckInReply
import dz.hirz.shared.api.IngestSignal
import dz.hirz.shared.api.LatLon
import dz.hirz.shared.signal.AlarmKind
import dz.hirz.shared.signal.FixSource
import kotlinx.serialization.builtins.ListSerializer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** SHA-256 hex; the platform provides it. */
expect fun sha256Hex(text: String): String

@OptIn(ExperimentalUuidApi::class)
fun newClientId(): String = Uuid.random().toString()

/** Builds the signals a companion sends. A heartbeat carries no position: "alive without a fix" is a state of its own. */
object CompanionSignals {
    fun heartbeat(battery: Int?, charging: Boolean?, @Suppress("UNUSED_PARAMETER") nowMs: Long) =
        IngestSignal(clientId = newClientId(), battery = battery, charging = charging, heartbeat = true)

    fun fix(at: LatLon, accuracyM: Double?, fixTimeMs: Long, source: FixSource, speedKmh: Double?, heading: Double?, battery: Int?, charging: Boolean?) =
        IngestSignal(
            clientId = newClientId(), fixTimeMs = fixTimeMs, position = at, accuracyM = accuracyM, fixSource = source,
            speedKmh = speedKmh, heading = heading, battery = battery, charging = charging,
        )

    fun sos() = IngestSignal(clientId = newClientId(), alarms = setOf(AlarmKind.SOS))

    fun checkIn(reply: CheckInReply) = IngestSignal(clientId = newClientId(), checkInReply = reply)
}

/**
 * Signals waiting for upload. Alarms go to the front and are never dropped; on overflow the oldest ordinary
 * signal goes first (design §6.2). Upload in batches of at most 100.
 */
class SignalQueue(private val capacity: Int = 2_000) {
    private val items = ArrayList<IngestSignal>()

    val size get() = items.size
    fun snapshot(): List<IngestSignal> = items.toList()

    private fun IngestSignal.isAlarm() = alarms.isNotEmpty()

    fun add(s: IngestSignal) {
        if (s.isAlarm()) items.add(items.indexOfFirst { !it.isAlarm() }.takeIf { it >= 0 } ?: items.size, s) else items.add(s)
        while (items.size > capacity) {
            val oldestOrdinary = items.indexOfFirst { !it.isAlarm() }
            if (oldestOrdinary < 0) break
            items.removeAt(oldestOrdinary)
        }
    }

    fun nextBatch(max: Int = 100): List<IngestSignal> = items.take(max)

    fun acknowledge(clientIds: Set<String>) { items.removeAll { it.clientId in clientIds } }

    fun toJson(): String = hirzJson.encodeToString(ListSerializer(IngestSignal.serializer()), items)

    companion object {
        fun fromJson(json: String?, capacity: Int = 2_000): SignalQueue = SignalQueue(capacity).also { q ->
            json?.let { runCatching { hirzJson.decodeFromString(ListSerializer(IngestSignal.serializer()), it) }.getOrNull() }
                ?.forEach(q::add)
        }
    }
}

/** What the carrier sees after typing a PIN: always the same, whatever the PIN meant (design §12.3.11). */
enum class PinUiState { ACCEPTED }

data class PinSubmission(val uiState: PinUiState, val signal: IngestSignal)

object PinPad {
    /** The device only hashes; the server alone knows whether this was the cancel PIN, the duress PIN or neither. */
    fun submit(pin: String, salt: String): PinSubmission =
        PinSubmission(PinUiState.ACCEPTED, IngestSignal(clientId = newClientId(), pinHash = sha256Hex(salt + pin)))
}

/** Reporting interval: 60 s normally, 15 s for 10 min after SOS or while the server reports an open incident. */
class CompanionCadence(private val normalMs: Long = 60_000, private val highMs: Long = 15_000, private val highWindowMs: Long = 600_000) {
    private var highUntil = Long.MIN_VALUE
    private var serverHigh = false

    fun sosPressed(nowMs: Long) { highUntil = nowMs + highWindowMs }
    fun serverSaysHighRate(high: Boolean, @Suppress("UNUSED_PARAMETER") nowMs: Long) { serverHigh = high }
    fun intervalMs(nowMs: Long): Long = if (serverHigh || nowMs < highUntil) highMs else normalMs
}
