package dz.hirz.shared.api

import kotlinx.serialization.Serializable

/** A temporary public link to the live position, for police or a helper without the app (design §5.8). */
@Serializable
data class ShareRequest(val ttlMinutes: Int = 60)

@Serializable
data class ShareDto(val url: String, val expiresAtMs: Long)

/** What `GET /s/{token}/data` returns: the same facts the public page shows, nothing more. */
@Serializable
data class ShareDataDto(
    val assetName: String,
    val last: PositionDto? = null,
    val lastAgeS: Long? = null,
    val track: List<PositionDto> = emptyList(),
    val expiresAtMs: Long,
)
