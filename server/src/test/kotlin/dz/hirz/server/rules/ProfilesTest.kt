package dz.hirz.server.rules

import dz.hirz.shared.api.RuleId
import dz.hirz.shared.api.RuleOverride
import dz.hirz.shared.api.Tier
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.asset.Condition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilesTest {
    private fun tier(p: Profile, r: RuleId) = p.rules[r]?.takeIf { it.enabled }?.tier

    @Test
    fun childProfileIsTodaysBehavior() {
        val p = Profiles.of(AssetType.CHILD, Condition.NONE)
        assertEquals(Tier.P1, tier(p, RuleId.PLACE_EXIT))
        assertEquals(Tier.P1, tier(p, RuleId.FORBIDDEN_ENTRY))
        assertEquals(Tier.P1, tier(p, RuleId.HAZARD_PROXIMITY))
        listOf(RuleId.NIGHT_EXIT, RuleId.RUN_AWAY_SPEED, RuleId.PAST_ADDRESS_HEADING, RuleId.CURFEW)
            .forEach { assertNull(tier(p, it), "$it is not a child rule") }
    }

    @Test
    fun autismMakesHazardP0() {
        val p = Profiles.of(AssetType.CHILD, Condition.AUTISM)
        assertEquals(Tier.P0, tier(p, RuleId.HAZARD_PROXIMITY))
        assertEquals(Tier.P0, tier(p, RuleId.RUN_AWAY_SPEED))
        assertEquals(1, p.hazardEntryReadings)
        assertEquals(2, Profiles.of(AssetType.CHILD, Condition.NONE).hazardEntryReadings)
    }

    @Test
    fun elderGetsNightExit() {
        val elder = Profiles.of(AssetType.ELDER, Condition.NONE)
        assertEquals(Tier.P1, tier(elder, RuleId.NIGHT_EXIT))
        assertNull(tier(elder, RuleId.PAST_ADDRESS_HEADING))
        assertEquals(Tier.P1, tier(Profiles.of(AssetType.ELDER, Condition.DEMENTIA), RuleId.PAST_ADDRESS_HEADING))
        assertTrue(elder.nightWindow.isNotEmpty())
        val teen = Profiles.of(AssetType.TEEN, Condition.NONE)
        assertEquals(Tier.P2, tier(teen, RuleId.CURFEW))
        assertEquals(Tier.P1, tier(teen, RuleId.COASTAL_NIGHT))
    }

    @Test
    fun overridesApplyAndP0IsNeverSwitchedOff() {
        val autism = Profiles.of(AssetType.CHILD, Condition.AUTISM, listOf(RuleOverride(RuleId.HAZARD_PROXIMITY, tier = Tier.P1)))
        assertEquals(Tier.P1, tier(autism, RuleId.HAZARD_PROXIMITY))
        assertEquals(Tier.P0, autism.rules.getValue(RuleId.HAZARD_PROXIMITY).defaultTier)
        val before = Profiles.of(AssetType.CHILD, Condition.AUTISM)
        assertEquals("p0_cannot_be_disabled",
            Profiles.validate(before, Profiles.of(AssetType.CHILD, Condition.AUTISM, listOf(RuleOverride(RuleId.HAZARD_PROXIMITY, enabled = false))), isOwner = true))
        assertNull(Profiles.validate(before, autism, isOwner = true))
        assertEquals("owner_only", Profiles.validate(before, autism, isOwner = false), "a guardian may not lower a tier")
        val child = Profiles.of(AssetType.CHILD, Condition.NONE)
        val raised = Profiles.of(AssetType.CHILD, Condition.NONE, listOf(RuleOverride(RuleId.PLACE_EXIT, tier = Tier.P0)))
        assertNull(Profiles.validate(child, raised, isOwner = false), "a guardian may raise a tier")
        assertEquals("owner_only", Profiles.validate(raised, child, isOwner = false), "dropping the raise lowers it again")
        assertEquals("unknown_rule", Profiles.validate(child,
            Profiles.of(AssetType.CHILD, Condition.NONE, listOf(RuleOverride(RuleId.NIGHT_EXIT))), isOwner = true))
        assertFalse(Profiles.of(AssetType.CHILD, Condition.NONE, listOf(RuleOverride(RuleId.PLACE_EXIT, enabled = false)))
            .rules.getValue(RuleId.PLACE_EXIT).enabled)
    }

    @Test
    fun livestockProfileDefaults() {
        val p = Profiles.of(AssetType.LIVESTOCK, Condition.NONE)
        mapOf(
            RuleId.HERD_NIGHT_MOVE to Tier.P1, RuleId.NIGHT_EXIT to Tier.P1, RuleId.PLACE_EXIT to Tier.P2,
            RuleId.VEHICLE_SPEED_UNEXPLAINED to Tier.P1, RuleId.HERD_DISPERSION to Tier.P2, RuleId.COLLAR_CUT to Tier.P1,
            RuleId.ANIMAL_STILL to Tier.P2, RuleId.HAZARD_PROXIMITY to Tier.P1,
        ).forEach { (r, t) -> assertEquals(t, tier(p, r), "$r") }
        assertTrue(p.lateTolerant, "collars store and forward")
        assertEquals(6 * 3_600_000L, p.silenceAfterMs, "pastures without coverage")
        val child = Profiles.of(AssetType.CHILD, Condition.NONE)
        assertFalse(child.lateTolerant)
        assertNull(child.silenceAfterMs, "people keep the global silence threshold")
        assertNull(tier(child, RuleId.HERD_NIGHT_MOVE))
    }
}
