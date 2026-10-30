package dz.hirz.shared.api

import dz.hirz.shared.asset.Condition
import kotlinx.serialization.Serializable

/** One rule in an asset's profile: [locked] when its default is P0, so nobody can switch it off. */
@Serializable
data class RuleSettingDto(val rule: RuleId, val enabled: Boolean, val tier: Tier, val defaultTier: Tier, val locked: Boolean = false)

/** A night outing the owner expects, e.g. Fajr prayer at the mosque: leaving home toward [placeId] in [window] is fine. */
@Serializable
data class NightException(val placeId: String, val window: ActiveWindow)

/** [birthDate]: ISO yyyy-MM-dd or null. [nightWindow]: Africa/Algiers time. */
@Serializable
data class ProfileDto(
    val condition: Condition,
    val rules: List<RuleSettingDto>,
    val nightWindow: List<ActiveWindow>,
    val birthDate: String? = null,
    val conditionOnSheet: Boolean = false,
    val nightExceptions: List<NightException> = emptyList(),
)

/** [tier] null keeps the default tier. */
@Serializable
data class RuleOverride(val rule: RuleId, val enabled: Boolean = true, val tier: Tier? = null)

/** Every field is optional; [overrides] replaces the whole list when given. */
@Serializable
data class ProfileUpdate(
    val condition: Condition? = null,
    val overrides: List<RuleOverride>? = null,
    val nightWindow: List<ActiveWindow>? = null,
    val birthDate: String? = null,
    val conditionOnSheet: Boolean? = null,
    val nightExceptions: List<NightException>? = null,
)
