package dz.hirz.shared.asset

import kotlinx.serialization.Serializable

/** Kinds of protected asset; each selects a default behavior profile (design §1, §4.4). */
@Serializable
enum class AssetType {
    CHILD, TEEN, ELDER, SPECIAL_NEEDS,
    CAR, MOTORCYCLE, BICYCLE, BUS, TRUCK, MACHINERY,
    LIVESTOCK, PET, BAG, GENERATOR, LONE_WORKER,
}
