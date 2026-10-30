package dz.hirz.server.people

import dz.hirz.server.rules.Profiles
import dz.hirz.shared.api.RuleId
import dz.hirz.shared.asset.AssetType
import dz.hirz.shared.asset.Condition
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

private val P5_RULES = listOf(RuleId.NIGHT_EXIT, RuleId.HAZARD_PROXIMITY, RuleId.RUN_AWAY_SPEED, RuleId.PAST_ADDRESS_HEADING, RuleId.CURFEW, RuleId.COASTAL_NIGHT)

class PeopleScenariosTest {
    /** AC-19: every P5 rule has a scenario that raises it and one where it must stay quiet. */
    @Test
    fun everyScenarioHoldsUnderItsOwnProfile() {
        val all = PeopleScenarios.all()
        assertTrue(all.size >= 12, "the P5 library is present")
        val failures = all.mapNotNull { s ->
            val got = runCatching { PeopleScenarios.run(s) }.getOrElse { return@mapNotNull "${s.name}: ${it.message}" }
            if (got != s.expect.toSet()) "${s.name}: open $got, expected ${s.expect.toSet()}" else null
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        P5_RULES.forEach { r ->
            assertTrue(all.any { it.positiveFor == r.name }, "no positive scenario for $r")
            assertTrue(all.any { r.name !in it.expect }, "no negative scenario for $r")
        }
    }

    /**
     * The confusion matrix: every scenario under every person profile. A P5 rule may only ever fire under a profile that
     * has it; any other cell is a false alarm by construction.
     */
    @Test
    fun confusionMatrixHasNoOffProfileAlarms() {
        val profiles = listOf(
            "child" to (AssetType.CHILD to Condition.NONE), "teen" to (AssetType.TEEN to Condition.NONE),
            "elder" to (AssetType.ELDER to Condition.NONE), "dementia" to (AssetType.ELDER to Condition.DEMENTIA),
            "autism" to (AssetType.CHILD to Condition.AUTISM),
        )
        val matrix = profiles.associate { (label, tc) ->
            val raised = PeopleScenarios.all().flatMap { s -> PeopleScenarios.run(s, tc.first, tc.second) }
            label to P5_RULES.associateWith { r -> raised.count { it == r.name } }
        }
        println("P5 confusion matrix (scenarios raising each rule, per profile):")
        println("profile".padEnd(10) + P5_RULES.joinToString(" ") { it.name.take(14).padEnd(14) })
        matrix.forEach { (label, row) -> println(label.padEnd(10) + P5_RULES.joinToString(" ") { row.getValue(it).toString().padEnd(14) }) }
        val off = profiles.flatMap { (label, tc) ->
            val allowed = Profiles.defaults(tc.first, tc.second).keys
            P5_RULES.filter { it !in allowed && matrix.getValue(label).getValue(it) > 0 }.map { "$label raised $it" }
        }
        if (off.isNotEmpty()) fail(off.joinToString("\n"))
    }
}
