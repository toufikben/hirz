package dz.hirz.server.trials

import dz.hirz.shared.rules.FalseAlarmBudget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class BudgetDocTest {
    /** The published budget is the one the gate uses: every line's id and limit appear in the doc's table. */
    @Test
    fun publishedBudgetMatchesTheCode() {
        val doc = listOf(File("../docs/research/false-alarm-budget.md"), File("docs/research/false-alarm-budget.md")).first { it.exists() }.readText()
        FalseAlarmBudget.LINES.forEach { (id, limit) ->
            assertTrue(doc.lines().any { "`$id`" in it && limit in it }, "the doc's row for $id must say \"$limit\"")
        }
    }
}
