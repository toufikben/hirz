package dz.hirz.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A place's shape. Coordinates are explicit lat/lon objects; the discriminator field is "type". */
@Serializable
sealed interface ShapeDto {
    @Serializable
    @SerialName("circle")
    data class Circle(val center: LatLon, val radiusM: Double) : ShapeDto {
        init { require(radiusM in 10.0..50_000.0) { "radius must be 10 m – 50 km" } }
    }

    /** At least three distinct points; the server closes the ring. */
    @Serializable
    @SerialName("polygon")
    data class Polygon(val points: List<LatLon>) : ShapeDto {
        init { require(points.distinct().size >= 3) { "a polygon needs at least three distinct points" } }
    }
}

@Serializable
enum class PlaceKind {
    @SerialName("home") HOME,
    @SerialName("school") SCHOOL,
    @SerialName("mosque") MOSQUE,
    @SerialName("lessons") LESSONS,
    @SerialName("park") PARK,
    @SerialName("relative") RELATIVE,
    @SerialName("pasture") PASTURE,
    @SerialName("barn") BARN,
    @SerialName("parking") PARKING,
    @SerialName("route") ROUTE,
    @SerialName("forbidden") FORBIDDEN,
    @SerialName("hazard") HAZARD,
    /** An old address a person with dementia may head for (P5). */
    @SerialName("past_address") PAST_ADDRESS,
    /** A shore point watched at night for teens; set by hand, never promoted (P5). */
    @SerialName("coast") COAST,
    @SerialName("other") OTHER,
}

/** Local Africa/Algiers time. Days are ISO (1 = Monday … 7 = Sunday); minutes from midnight, end exclusive. */
@Serializable
data class ActiveWindow(val daysOfWeek: Set<Int>, val startMinute: Int, val endMinute: Int) {
    init {
        require(daysOfWeek.isNotEmpty() && daysOfWeek.all { it in 1..7 }) { "days must be 1..7" }
        require(startMinute in 0 until 1440 && endMinute in 1..1440 && startMinute < endMinute) { "invalid time window" }
    }
}

@Serializable
data class CreatePlaceRequest(
    val name: String,
    val kind: PlaceKind,
    val shape: ShapeDto,
    val marginM: Int = 50,
    val activeHours: List<ActiveWindow> = emptyList(),
)

@Serializable
data class PlaceDto(
    val id: String,
    val circleId: String,
    val name: String,
    val kind: PlaceKind,
    val shape: ShapeDto,
    val marginM: Int,
    val activeHours: List<ActiveWindow> = emptyList(),
)
