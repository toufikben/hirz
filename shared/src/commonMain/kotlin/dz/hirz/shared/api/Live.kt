package dz.hirz.shared.api

import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.signal.FixSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One stored position, as shown on the map and in history. Age is always computable: fix time is mandatory. */
@Serializable
data class PositionDto(
    val assetId: String,
    val fixTimeMs: Long,
    val serverTimeMs: Long,
    val position: LatLon,
    val accuracyM: Double? = null,
    val accuracyEstimated: Boolean = false,
    val fixSource: FixSource? = null,
    val speedKmh: Double? = null,
    val heading: Double? = null,
    val battery: Int? = null,
)

@Serializable
data class AssetLiveDto(
    val assetId: String,
    val circleId: String,
    val name: String,
    val type: AssetType,
    val last: PositionDto? = null,
    val lastHeartbeatAtMs: Long? = null,
)

/** Server → app over `WS /v1/live`; the field "type" names the event. */
@Serializable
sealed interface LiveEvent {
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val assets: List<AssetLiveDto>, val incidents: List<IncidentDto>, val serverTimeMs: Long) : LiveEvent

    @Serializable
    @SerialName("position")
    data class PositionUpdate(val position: PositionDto) : LiveEvent

    @Serializable
    @SerialName("incident")
    data class IncidentUpdate(val incident: IncidentDto) : LiveEvent

    @Serializable
    @SerialName("member_removed")
    data class MemberRemoved(val circleId: String) : LiveEvent
}
