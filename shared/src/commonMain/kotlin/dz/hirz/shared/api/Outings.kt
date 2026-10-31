package dz.hirz.shared.api

import kotlinx.serialization.Serializable

/** "We're going out" (design §1 "accompanied by", §12.2 LEASH_BREAK): a guardian takes the asset out for [hours] (≤ 6). */
@Serializable
data class OutingRequest(val hours: Int = 2)

@Serializable
data class OutingDto(val id: String, val assetId: String, val startsAtMs: Long, val endsAtMs: Long)

/**
 * The accompanying guardian's phone, every few seconds during the outing: where it is, and which of the asset's BLE tags
 * it hears right now ([seenTags]).
 */
@Serializable
data class OutingPing(val position: LatLon, val accuracyM: Double, val atMs: Long, val seenTags: List<String> = emptyList())
