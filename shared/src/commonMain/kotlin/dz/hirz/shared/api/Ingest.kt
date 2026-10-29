package dz.hirz.shared.api

import dz.hirz.shared.signal.AlarmKind
import dz.hirz.shared.signal.FixSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CheckInReply {
    @SerialName("ok") OK,
    @SerialName("help") HELP,
}

/**
 * One signal from a companion. [clientId] makes resending safe. [pinHash] = sha256(pinSalt + typed PIN);
 * the server decides what it means and never tells the device (design §12.3.11).
 */
@Serializable
data class IngestSignal(
    val clientId: String,
    val fixTimeMs: Long? = null,
    val position: LatLon? = null,
    val accuracyM: Double? = null,
    val fixSource: FixSource? = null,
    val speedKmh: Double? = null,
    val heading: Double? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val alarms: Set<AlarmKind> = emptySet(),
    val heartbeat: Boolean = true,
    val checkInReply: CheckInReply? = null,
    val pinHash: String? = null,
)

@Serializable
data class IngestBatch(val signals: List<IngestSignal>)

@Serializable
data class Rejection(val clientId: String, val reason: String)

/** [highRate] asks the companion to report faster while its asset has an open P0/P1 incident. */
@Serializable
data class IngestResult(
    val accepted: Int,
    val duplicates: Int,
    val rejected: List<Rejection> = emptyList(),
    val highRate: Boolean = false,
)
