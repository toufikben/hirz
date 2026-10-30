package dz.hirz.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What is expected of a vehicle now (design §12.1). Theft rules run only while ARMED. */
@Serializable
enum class VehicleMode {
    @SerialName("armed") ARMED,
    @SerialName("disarmed") DISARMED,
    @SerialName("authorized") AUTHORIZED,
    @SerialName("maintenance") MAINTENANCE,
}

@Serializable
data class ModeRequest(val mode: VehicleMode)

/** A drive window for the owner or a named driver; it ends by itself. */
@Serializable
data class AuthorizationRequest(val hours: Int = 2, val driverName: String? = null, val memberId: String? = null)

@Serializable
data class AuthorizationDto(val id: String, val startsAtMs: Long, val endsAtMs: Long, val driverName: String? = null, val memberId: String? = null)

/** Armed hours in Algiers local time; a window across midnight is two windows. */
@Serializable
data class ScheduleRequest(val windows: List<ActiveWindow>)

/** [reason]: manual, authorized, schedule, auto_arm, expired or default. */
@Serializable
data class VehicleStateDto(
    val mode: VehicleMode,
    val reason: String,
    val sinceMs: Long? = null,
    val authorization: AuthorizationDto? = null,
    val armedPosition: LatLon? = null,
    val ignition: Boolean? = null,
    val schedule: List<ActiveWindow> = emptyList(),
)
