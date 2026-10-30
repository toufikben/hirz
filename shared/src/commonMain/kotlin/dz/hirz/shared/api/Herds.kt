package dz.hirz.shared.api

import kotlinx.serialization.Serializable

@Serializable
data class CreateHerdRequest(val name: String)

@Serializable
data class HerdMemberRequest(val assetId: String)

@Serializable
data class LeaderRequest(val assetId: String)

/** A herd (design §1 groups): livestock of one circle, one leader carrying the tracker, the rest often on BLE collars. */
@Serializable
data class HerdDto(
    val id: String,
    val circleId: String,
    val name: String,
    val members: List<String> = emptyList(),
    val leaderId: String? = null,
    /** Weekly market hours (Africa/Algiers) when moving the herd is expected. */
    val marketWindow: List<ActiveWindow> = emptyList(),
    /** The owner's Eid al-Adha date for this herd (ISO), overriding the built-in estimate. */
    val eidDate: String? = null,
)

/** Weekly market hours for a herd; empty clears them. */
@Serializable
data class MarketRequest(val window: List<ActiveWindow>)

/** The announced Eid al-Adha date (ISO yyyy-MM-dd); null returns to the built-in estimate. */
@Serializable
data class EidRequest(val date: String?)
