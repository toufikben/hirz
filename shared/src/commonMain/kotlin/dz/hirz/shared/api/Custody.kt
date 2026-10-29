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
data class ConsentRequest(val otherCircleCode: String)

/** [state]: `pending`, `active` or `revoked`. */
@Serializable
data class ConsentDto(val id: String, val assetId: String, val fromCircleId: String, val toCircleId: String, val state: String)
