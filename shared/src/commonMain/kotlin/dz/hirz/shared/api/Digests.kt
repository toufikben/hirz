package dz.hirz.shared.api

import kotlinx.serialization.Serializable

/**
 * One line of an evening digest: [count] P2 findings of [rule] on one asset today. A rule that exists only for one
 * condition is shown as `PERSON_ALERT`, so the digest never tells who has which condition.
 */
@Serializable
data class DigestItem(val assetId: String, val assetName: String, val rule: String, val count: Int, val firstAt: Long)

/** The day's quiet findings for a circle, gathered at 20:00 Africa/Algiers; [day] is ISO (yyyy-mm-dd). */
@Serializable
data class DigestDto(val id: String, val day: String, val items: List<DigestItem>)
