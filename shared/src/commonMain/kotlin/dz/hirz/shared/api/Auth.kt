package dz.hirz.shared.api

import kotlinx.serialization.Serializable

@Serializable
data class MeDto(val memberId: String, val displayName: String?, val lang: String)

@Serializable
data class ApiErrorDto(val error: String, val message: String? = null)

/** What a paired companion may know about itself (used by its self-test screen). */
@Serializable
data class SourceStatusDto(
    val sourceId: String,
    val assetId: String,
    val assetName: String,
    val circleName: String,
    val lastHeartbeatAtMs: Long?,
    val lastFixAtMs: Long?,
)

@Serializable
data class PushTokenDto(val token: String, val platform: String = "android")
