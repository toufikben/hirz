package dz.hirz.shared.asset

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Kinds of protected asset; each selects a default behavior profile (design §1, §4.4). */
@Serializable
enum class AssetType {
    @SerialName("child") CHILD,
    @SerialName("teen") TEEN,
    @SerialName("elder") ELDER,
    @SerialName("special_needs") SPECIAL_NEEDS,
    @SerialName("car") CAR,
    @SerialName("motorcycle") MOTORCYCLE,
    @SerialName("bicycle") BICYCLE,
    @SerialName("bus") BUS,
    @SerialName("truck") TRUCK,
    @SerialName("machinery") MACHINERY,
    @SerialName("livestock") LIVESTOCK,
    @SerialName("pet") PET,
    @SerialName("bag") BAG,
    @SerialName("generator") GENERATOR,
    @SerialName("lone_worker") LONE_WORKER,
}
