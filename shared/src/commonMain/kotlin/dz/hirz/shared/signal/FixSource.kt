package dz.hirz.shared.signal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a position was obtained; drives the accuracy we trust (design §3). Wire names follow §3. */
@Serializable
enum class FixSource {
    @SerialName("gnss") GNSS,
    @SerialName("wifi") WIFI,
    @SerialName("cell") CELL,
    @SerialName("ble-relay") BLE_RELAY,
    @SerialName("sms") SMS,
}
