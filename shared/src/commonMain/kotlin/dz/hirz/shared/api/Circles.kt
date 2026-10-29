package dz.hirz.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Role {
    @SerialName("owner") OWNER,
    @SerialName("guardian") GUARDIAN,
    @SerialName("viewer") VIEWER,
    @SerialName("contact") CONTACT,
    @SerialName("temporary") TEMPORARY,
}

@Serializable
data class CreateCircleRequest(val name: String)

@Serializable
data class CircleDto(val id: String, val name: String, val myRole: Role)

@Serializable
data class MemberDto(
    val memberId: String,
    val displayName: String? = null,
    val role: Role,
    val priority: Int,
    val expiresAtMs: Long? = null,
)

/** [memberExpiresAtMs] is required for TEMPORARY members (babysitter, driver) and ignored otherwise. */
@Serializable
data class CreateInviteRequest(val role: Role, val expiresInHours: Int = 24, val memberExpiresAtMs: Long? = null)

@Serializable
data class InviteDto(val code: String, val deepLink: String, val expiresAtMs: Long)

@Serializable
data class UpdateMemberRequest(val role: Role? = null, val priority: Int? = null)
