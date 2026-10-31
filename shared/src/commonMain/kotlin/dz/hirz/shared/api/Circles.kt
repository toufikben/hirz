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
/** A family, or an institution (school, transport company, elders' home) that families share children with (P9). */
@Serializable
enum class CircleKind { @SerialName("family") FAMILY, @SerialName("institution") INSTITUTION }

@Serializable
data class CreateCircleRequest(val name: String, val kind: CircleKind = CircleKind.FAMILY)

@Serializable
data class CircleDto(val id: String, val name: String, val myRole: Role, val kind: CircleKind = CircleKind.FAMILY)

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

/** A no-app contact (grandmother, neighbour) reached by SMS; [phone] is E.164 or a local 0XXXXXXXXX number. */
@Serializable
data class ContactRequest(val name: String, val phone: String, val lang: String = "ar")
