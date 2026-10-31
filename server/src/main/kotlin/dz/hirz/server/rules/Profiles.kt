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
    /** Store-and-forward devices: fixes are judged at their own time even when they arrive hours late (P6). */
    val lateTolerant: Boolean = false,
    /** The place a night exit watches: home for people, the barn for livestock. */
    val nightHome: dz.hirz.shared.api.PlaceKind = dz.hirz.shared.api.PlaceKind.HOME,
    /** Night from the real sunset and sunrise at the night home (livestock by default) instead of [nightWindow]. */
    val nightBySun: Boolean = false,
    /** Ramadan evening (people): from 21:30 to 23:30 is the tarawih, not night, unless the owner turned that off. */
    val ramadanEvening: Boolean = false,
    /** In the two weeks before Eid al-Adha (livestock only): tiers raised, a smaller share of the herd is a night move. */
    val eidSeason: Boolean = false,
    /** Silence threshold for this profile; null keeps the global one. */
    val silenceAfterMs: Long? = null,
    /** Overrides naming a rule this profile does not have; validate() refuses them. */
    val unknownOverrides: Set<RuleId> = emptySet(),
) {
    /** The tier [rule] fires at, or null when it does not run for this asset. */
    fun tierOf(rule: RuleId): Tier? = rules[rule]?.takeIf { it.enabled }?.tier

    /** Whether [at] is night for this asset, [where] being its night home (null: the fixed window). */
    fun isNight(at: java.time.Instant, where: dz.hirz.shared.api.LatLon?): Boolean {
        val local = at.atZone(java.time.ZoneId.of("Africa/Algiers"))
        val minute = local.hour * 60 + local.minute
        if (ramadanEvening && minute in 21 * 60 + 30 until 23 * 60 + 30) return false
        if (nightBySun && where != null) return dz.hirz.server.calendar.SolarTimes.isNight(at, where)
        return dz.hirz.server.places.Geometry.isActive(nightWindow, at)
    }
}

/**
 * Behavior profiles (design §4.4), a pure function. Adding an asset type or a condition is a change here only;
 * incidents, escalation and notification read the tier and nothing else (AC-20).
 */
object Profiles {
    private val PEOPLE = setOf(AssetType.CHILD, AssetType.TEEN, AssetType.ELDER, AssetType.SPECIAL_NEEDS, AssetType.LONE_WORKER)

    /** Herds leave for the pasture at dawn: their night ends at 04:30. */
    val LIVESTOCK_NIGHT = listOf(ActiveWindow((1..7).toSet(), 22 * 60, 1440), ActiveWindow((1..7).toSet(), 0, 4 * 60 + 30))

    /** 22:00–06:00 every day, Africa/Algiers. */
    val DEFAULT_NIGHT = listOf(ActiveWindow((1..7).toSet(), 22 * 60, 1440), ActiveWindow((1..7).toSet(), 0, 6 * 60))

    fun defaults(type: AssetType, condition: Condition): Map<RuleId, Tier> = buildMap {
        put(RuleId.PLACE_EXIT, Tier.P1)
        put(RuleId.FORBIDDEN_ENTRY, Tier.P1)
        // A pet or a herd at the water or the railway matters too (it was FORBIDDEN_ENTRY before P5).
        put(RuleId.HAZARD_PROXIMITY, Tier.P1)
        if (type == AssetType.LIVESTOCK) {
            // Leaving the pasture by day is grazing more often than theft; the barn at night is the other way round.
            put(RuleId.PLACE_EXIT, Tier.P2)
            put(RuleId.NIGHT_EXIT, Tier.P1)
            put(RuleId.HERD_NIGHT_MOVE, Tier.P1)
            put(RuleId.HERD_DISPERSION, Tier.P2)
            put(RuleId.VEHICLE_SPEED_UNEXPLAINED, Tier.P1)
            put(RuleId.COLLAR_CUT, Tier.P1)
            put(RuleId.ANIMAL_STILL, Tier.P2)
            // Only where the owner set a routine (the barn at night).
            put(RuleId.MISSED_ARRIVAL, Tier.P1)
            return@buildMap
        }
        if (type !in PEOPLE) return@buildMap
        // Only where the owner drew a shore place; for any person, not only teens.
        put(RuleId.COASTAL_NIGHT, Tier.P1)
        // Only during an outing the guardian started.
        put(RuleId.LEASH_BREAK, Tier.P1)
        // Only where the owner set a routine (P7).
        put(RuleId.MISSED_ARRIVAL, Tier.P1)
        put(RuleId.STATIONARY_AFTER_DISMISSAL, Tier.P1)
        put(RuleId.UNUSUAL_STOP, Tier.P2)
        if (type == AssetType.TEEN) put(RuleId.CURFEW, Tier.P2)
        if (type == AssetType.ELDER) { put(RuleId.NIGHT_EXIT, Tier.P1); put(RuleId.PACING_LOOP, Tier.P2) }
        when (condition) {
            Condition.DEMENTIA -> { put(RuleId.PAST_ADDRESS_HEADING, Tier.P1); put(RuleId.NIGHT_EXIT, Tier.P1) }
            Condition.AUTISM -> { put(RuleId.HAZARD_PROXIMITY, Tier.P0); put(RuleId.RUN_AWAY_SPEED, Tier.P0); put(RuleId.LEASH_BREAK, Tier.P0) }
            Condition.NONE -> {}
        }
    }

    fun of(
        type: AssetType, condition: Condition, overrides: List<RuleOverride> = emptyList(), nightWindow: List<ActiveWindow>? = null,
        nightExceptions: List<dz.hirz.shared.api.NightException> = emptyList(), nightBySun: Boolean? = null,
    ): Profile {
        val byRule = overrides.associateBy { it.rule }
        val rules = defaults(type, condition).mapValues { (rule, def) ->
            val o = byRule[rule]
            RuleSetting(enabled = o?.enabled ?: true, tier = o?.tier ?: def, defaultTier = def)
        }
        val night = nightWindow?.takeIf { it.isNotEmpty() } ?: if (type == AssetType.LIVESTOCK) LIVESTOCK_NIGHT else DEFAULT_NIGHT
        return Profile(condition, rules, night,
            hazardEntryReadings = if (condition == Condition.AUTISM) 1 else 2, nightExceptions = nightExceptions,
            lateTolerant = type == AssetType.LIVESTOCK,
            nightBySun = nightBySun ?: (type == AssetType.LIVESTOCK),
            nightHome = if (type == AssetType.LIVESTOCK) dz.hirz.shared.api.PlaceKind.BARN else dz.hirz.shared.api.PlaceKind.HOME,
            silenceAfterMs = if (type == AssetType.LIVESTOCK) 6 * 3_600_000L else null,
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

    /**
     * The calendar's effect (design §12): livestock in the two weeks before Eid al-Adha has every P2 rule raised to P1.
     * The calendar never creates a P0, and people are never affected.
     */
    fun seasonal(p: Profile, type: AssetType, at: java.time.Instant, eidOverride: java.time.LocalDate?, ramadanNightOff: Boolean = false): Profile {
        val day = at.atZone(java.time.ZoneId.of("Africa/Algiers")).toLocalDate()
        if (type in PEOPLE) {
            // Ramadan evenings: after iftar the family goes out for the tarawih; that is not a night exit (design §12).
            return if (!ramadanNightOff && dz.hirz.server.calendar.AlgerianCalendar.inRamadan(day)) p.copy(ramadanEvening = true) else p
        }
        if (type != AssetType.LIVESTOCK) return p
        if (!dz.hirz.server.calendar.AlgerianCalendar.inEidSeason(day, eidOverride)) return p
        return p.copy(eidSeason = true, rules = p.rules.mapValues { (_, s) -> if (s.tier == Tier.P2) s.copy(tier = Tier.P1) else s })
    }
}
