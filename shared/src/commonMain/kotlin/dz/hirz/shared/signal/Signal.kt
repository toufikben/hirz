package dz.hirz.shared.signal

import kotlinx.serialization.Serializable

/** A WGS84 position. `accuracyEstimated` is true when the source gave no accuracy and we estimated it. */
@Serializable
data class Position(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val accuracyEstimated: Boolean,
) {
    init {
        require(lat in -90.0..90.0) { "latitude out of range: $lat" }
        require(lon in -180.0..180.0) { "longitude out of range: $lon" }
        require(accuracyM == null || accuracyM >= 0.0) { "accuracy must be >= 0: $accuracyM" }
    }
}

/**
 * The normalized signal every source is translated into (design §3).
 * The heartbeat is independent of the position: a live source without a fix is not a dead source.
 */
@Serializable
data class Signal(
    val sourceId: String,
    val assetId: String?,
    val fixTimeMs: Long?,
    val serverTimeMs: Long,
    val position: Position?,
    val fixSource: FixSource?,
    val speedKmh: Double?,
    val heading: Double?,
    val heartbeat: Boolean,
    val battery: Int?,
    val charging: Boolean?,
    val externalPower: Boolean?,
    val ignition: Boolean?,
    val motion: Boolean?,
    val tilt: Boolean?,
    val alarms: Set<AlarmKind>,
) {
    /** Age of the fix at [nowMs], measured from the fix time; null when there is no fix. */
    fun ageMs(nowMs: Long): Long? = fixTimeMs?.let { nowMs - it }
}
