package dz.hirz.shared.api

import kotlinx.serialization.Serializable

/**
 * A temporary custodian (a grandparent on Thursday, a school trip): asked first if something happens. With
 * [mutePlaceExit] place exits are recorded but not raised; SOS, duress and silence are never muted.
 */
@Serializable
data class HandoverRequest(val memberId: String, val startsAtMs: Long, val endsAtMs: Long, val mutePlaceExit: Boolean = false)

@Serializable
data class HandoverDto(val id: String, val assetId: String, val memberId: String, val startsAtMs: Long, val endsAtMs: Long, val mutePlaceExit: Boolean)

/** A one-time code an owner gives to the owner of another circle, to share custody of an asset. */
@Serializable
data class LinkCodeDto(val code: String, val expiresAtMs: Long)

@Serializable
data class ConsentRequest(
    val otherCircleCode: String,
    /** With an institution (P9): the hours it may see the child; required. */
    val windows: List<ActiveWindow> = emptyList(),
    /** With an institution: the last day (ISO); the end of the school year (30 June) by default. */
    val expiresOn: String? = null,
)

/** [state]: `pending`, `active` or `revoked`. */
@Serializable
data class ConsentDto(
    val id: String, val assetId: String, val fromCircleId: String, val toCircleId: String, val state: String,
    /** `custody` (two families) or `institution` (P9). */
    val scope: String = "custody",
    val windows: List<ActiveWindow> = emptyList(),
    val expiresAtMs: Long? = null,
)
