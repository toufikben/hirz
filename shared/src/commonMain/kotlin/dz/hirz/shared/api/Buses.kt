package dz.hirz.shared.api

import kotlinx.serialization.Serializable

/** A stop of a bus run: due at [dueMin] (minutes from midnight, Africa/Algiers), reached within [radiusM]. */
@Serializable
data class BusStopRequest(val name: String, val at: LatLon, val radiusM: Double = 50.0, val dueMin: Int)

/**
 * A bus run (P9): a corridor ([path], [widthM] wide) with stops in time order, on [days] (ISO 1 = Monday … 7 = Sunday)
 * from [startMin] to [endMin]. Inside that window the bus is in route mode.
 */
@Serializable
data class BusRouteRequest(
    val name: String,
    val path: List<LatLon>,
    val widthM: Double = 60.0,
    val days: Set<Int>,
    val startMin: Int,
    val endMin: Int,
    val stops: List<BusStopRequest>,
)

@Serializable
data class BusStopDto(val seq: Int, val name: String, val at: LatLon, val radiusM: Double, val dueMin: Int)

@Serializable
data class BusRouteDto(
    val id: String, val assetId: String, val name: String, val path: List<LatLon>, val widthM: Double,
    val days: Set<Int>, val startMin: Int, val endMin: Int, val stops: List<BusStopDto>,
)
