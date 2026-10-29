package dz.hirz.shared.api

import dz.hirz.shared.asset.AssetType
import kotlinx.serialization.Serializable

@Serializable
data class CreateAssetRequest(val type: AssetType, val name: String, val descriptors: Map<String, String> = emptyMap())

@Serializable
data class SourceDto(
    val id: String,
    val kind: String,
    val adult: Boolean,
    val consentAtMs: Long? = null,
    val lastHeartbeatAtMs: Long? = null,
    val lastFixAtMs: Long? = null,
    val revoked: Boolean,
)

@Serializable
data class AssetDto(
    val id: String,
    val circleId: String,
    val type: AssetType,
    val name: String,
    val lifecycle: String,
    val sources: List<SourceDto> = emptyList(),
)

/** PINs are 4–8 digits; the cancel PIN and the duress PIN must differ (design §5, §12.3.11). */
@Serializable
data class CreatePairingRequest(val cancelPin: String? = null, val duressPin: String? = null)

@Serializable
data class PairingCodeDto(val code: String, val qrPayload: String, val expiresAtMs: Long)

@Serializable
data class PairRequest(
    val code: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val pushToken: String? = null,
    val carrierIsAdult: Boolean,
    val consentGiven: Boolean,
)

/** [sourceToken] is shown exactly once; the server keeps only its hash. */
@Serializable
data class PairResponse(
    val sourceId: String,
    val sourceToken: String,
    val assetName: String,
    val circleName: String,
    val pinSalt: String? = null,
)
