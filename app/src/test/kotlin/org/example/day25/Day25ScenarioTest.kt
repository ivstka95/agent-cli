package org.example.day25

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [Day 25] Guards the bundled scenarios: two long dialogues, each with a goal and 10–15 questions. */
class Day25ScenarioTest {

    @Test
    fun `loads exactly two scenarios, each with a goal and 10 to 15 questions`() {
        val scenarios = Day25Scenario.load()

        assertEquals(2, scenarios.size, "Day 25 verifies on two long scenarios")
        scenarios.forEach { s ->
            assertTrue(s.goal.isNotBlank(), "scenario '${s.name}' must have a goal (task memory)")
            assertTrue(
                s.questions.size in 10..15,
                "scenario '${s.name}' must have 10–15 questions but has ${s.questions.size}",
            )
            assertTrue(s.questions.all { it.isNotBlank() }, "no blank questions")
        }
    }
}
