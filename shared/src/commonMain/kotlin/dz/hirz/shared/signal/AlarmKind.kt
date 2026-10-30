package dz.hirz.shared.signal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Device-raised alarms carried by the signal contract (design §3). Wire names follow §3. */
@Serializable
enum class AlarmKind {
    @SerialName("sos") SOS,
    @SerialName("tamper") TAMPER,
    @SerialName("removal") REMOVAL,
    @SerialName("powerCut") POWER_CUT,
    @SerialName("jamming") JAMMING,
    @SerialName("fall") FALL,
    @SerialName("duress") DURESS,
    /** Vehicles (design §12.2): shock or tilt while parked, a door opened, the GNSS antenna disconnected. */
    @SerialName("vibration") VIBRATION,
    @SerialName("door") DOOR,
    @SerialName("antennaCut") ANTENNA_CUT,
}
