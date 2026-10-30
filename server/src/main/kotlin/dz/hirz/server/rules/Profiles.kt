package dz.hirz.server.rules

import dz.hirz.shared.api.ActiveWindow
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.RuleOverride
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.asset.Condition

data class RuleSetting(val enabled: Boolean, val tier: Tier, val defaultTier: Tier)

/** What runs for one asset and how urgent it is: the type's defaults, its condition, then the owner's overrides. */
data class Profile(
    val condition: Condition,
    val rules: Map<RuleId, RuleSetting>,
    val nightWindow: List<ActiveWindow>,
    /** Good readings inside a hazard place before it counts as entered. */
    val hazardEntryReadings: Int,
    val nightExceptions: List<dz.hirz.shared.api.NightException> = emptyList(),
    /** Overrides naming a rule this profile does not have; validate() refuses them. */
    val unknownOverrides: Set<RuleId> = emptySet(),
) {
    /** The tier [rule] fires at, or null when it does not run for this asset. */
    fun tierOf(rule: RuleId): Tier? = rules[rule]?.takeIf { it.enabled }?.tier
}

/**
 * Behavior profiles (design §4.4), a pure function. Adding an asset type or a condition is a change here only;
 * incidents, escalation and notification read the tier and nothing else (AC-20).
 */
object Profiles {
    private val PEOPLE = setOf(AssetType.CHILD, AssetType.TEEN, AssetType.ELDER, AssetType.SPECIAL_NEEDS, AssetType.LONE_WORKER)

    /** 22:00–06:00 every day, Africa/Algiers. */
    val DEFAULT_NIGHT = listOf(ActiveWindow((1..7).toSet(), 22 * 60, 1440), ActiveWindow((1..7).toSet(), 0, 6 * 60))

    fun defaults(type: AssetType, condition: Condition): Map<RuleId, Tier> = buildMap {
        put(RuleId.PLACE_EXIT, Tier.P1)
        put(RuleId.FORBIDDEN_ENTRY, Tier.P1)
        // A pet or a herd at the water or the railway matters too (it was FORBIDDEN_ENTRY before P5).
        put(RuleId.HAZARD_PROXIMITY, Tier.P1)
        if (type !in PEOPLE) return@buildMap
        // Only where the owner drew a shore place; for any person, not only teens.
        put(RuleId.COASTAL_NIGHT, Tier.P1)
        if (type == AssetType.TEEN) put(RuleId.CURFEW, Tier.P2)
        if (type == AssetType.ELDER) put(RuleId.NIGHT_EXIT, Tier.P1)
        when (condition) {
            Condition.DEMENTIA -> { put(RuleId.PAST_ADDRESS_HEADING, Tier.P1); put(RuleId.NIGHT_EXIT, Tier.P1) }
            Condition.AUTISM -> { put(RuleId.HAZARD_PROXIMITY, Tier.P0); put(RuleId.RUN_AWAY_SPEED, Tier.P0) }
            Condition.NONE -> {}
        }
    }

    fun of(
        type: AssetType, condition: Condition, overrides: List<RuleOverride> = emptyList(), nightWindow: List<ActiveWindow>? = null,
        nightExceptions: List<dz.hirz.shared.api.NightException> = emptyList(),
    ): Profile {
        val byRule = overrides.associateBy { it.rule }
        val rules = defaults(type, condition).mapValues { (rule, def) ->
            val o = byRule[rule]
            RuleSetting(enabled = o?.enabled ?: true, tier = o?.tier ?: def, defaultTier = def)
        }
        return Profile(condition, rules, nightWindow?.takeIf { it.isNotEmpty() } ?: DEFAULT_NIGHT,
            hazardEntryReadings = if (condition == Condition.AUTISM) 1 else 2, nightExceptions = nightExceptions,
            unknownOverrides = byRule.keys.filter { it !in rules }.toSet())
    }

    /**
     * Null when moving from [before] to [after] is allowed, else an error code. A default-P0 rule can never be switched
     * off; a guardian may only make rules more urgent; the owner may lower anything but a P0 rule's switch.
     */
    fun validate(before: Profile, after: Profile, isOwner: Boolean): String? {
        if (after.unknownOverrides.isNotEmpty()) return "unknown_rule"
        if (after.rules.values.any { it.defaultTier == Tier.P0 && !it.enabled }) return "p0_cannot_be_disabled"
        if (isOwner) return null
        for ((rule, now) in after.rules) {
            val was = before.rules[rule] ?: continue
            val wasTier = was.tier.takeIf { was.enabled }
            val nowTier = now.tier.takeIf { now.enabled }
            if (wasTier != null && (nowTier == null || nowTier.ordinal > wasTier.ordinal)) return "owner_only"
        }
        return null
    }
}
