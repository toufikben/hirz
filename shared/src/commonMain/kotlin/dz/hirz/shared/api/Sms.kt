package dz.hirz.shared.api

import kotlinx.serialization.Serializable

/** A one-time code that turns a family phone with a SIM into the circle's SMS gateway (design §12.3.3). */
@Serializable
data class GatewayCodeDto(val code: String, val expiresAtMs: Long)

@Serializable
data class GatewayPairRequest(val code: String, val pushToken: String? = null)

@Serializable
data class GatewayPairResponse(val token: String, val circleId: String)

@Serializable
data class OutboxItemDto(val id: Long, val to: String, val body: String)

/** [status] is `sent` or `failed`. */
@Serializable
data class SmsResultRequest(val status: String, val error: String? = null)

@Serializable
data class InboundSmsRequest(val from: String, val body: String, val receivedAtMs: Long)
