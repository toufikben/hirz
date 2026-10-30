package dz.hirz.shared.asset

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A person's condition that changes which rules run and how urgent they are (design §4.4). Set by the owner only;
 * never shown in a push, an SMS or the public share page.
 */
@Serializable
enum class Condition {
    @SerialName("none") NONE,
    @SerialName("dementia") DEMENTIA,
    @SerialName("autism") AUTISM,
}
