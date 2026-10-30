package dz.hirz.shared.api

import kotlinx.serialization.Serializable

// Convention: every nullable DTO field defaults to null, because the server omits nulls on the wire.

@Serializable
data class MeDto(val memberId: String, val displayName: String? = null, val lang: String)

@Serializable
data class ApiErrorDto(val error: String, val message: String? = null)

/** What a paired companion may know about itself (used by its self-test screen). */
@Serializable
data class SourceStatusDto(
    val sourceId: String,
    val assetId: String,
    val assetName: String,
    val circleName: String,
    val lastHeartbeatAtMs: Long? = null,
    val lastFixAtMs: Long? = null,
    /** Lets the companion pick its layout (the elder's simple mode); never the person's condition. */
    val assetType: dz.hirz.shared.asset.AssetType? = null,
    /** "age_18" when sharing is paused until the person consents on this phone. */
    val pausedReason: String? = null,
)

@Serializable
data class PushTokenDto(val token: String, val platform: String = "android")
