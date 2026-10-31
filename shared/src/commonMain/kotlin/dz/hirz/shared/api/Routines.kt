package dz.hirz.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ready-made routines (design §6.3); the fields not given in a request come from the template. */
@Serializable
enum class RoutineTemplate {
    @SerialName("school") SCHOOL,
    @SerialName("mosque") MOSQUE,
    @SerialName("lessons") LESSONS,
    @SerialName("pasture_day") PASTURE_DAY,
    @SerialName("barn_night") BARN_NIGHT,
}

/**
 * An expected day (design §1 Routine): on [days] (ISO 1 = Monday … 7 = Sunday), at [placeId] by [arriveByMin] and/or
 * leaving at [dismissalMin] (minutes from midnight, Africa/Algiers). [ramadanOffsetMin] shifts both during Ramadan.
 */
@Serializable
data class RoutineRequest(
    val placeId: String,
    val days: Set<Int> = emptySet(),
    val arriveByMin: Int? = null,
    val dismissalMin: Int? = null,
    val ramadanOffsetMin: Int = 0,
    val active: Boolean = true,
    val template: RoutineTemplate? = null,
)

@Serializable
data class RoutineDto(
    val id: String,
    val assetId: String,
    val placeId: String,
    val days: Set<Int>,
    val arriveByMin: Int? = null,
    val dismissalMin: Int? = null,
    val ramadanOffsetMin: Int = 0,
    val active: Boolean = true,
)
