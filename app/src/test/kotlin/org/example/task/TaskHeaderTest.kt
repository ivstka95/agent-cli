package org.example.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskHeaderTest {

    @Test
    fun `parses stage_complete true or false, and defaults to false when missing`() {
        assertTrue(TaskHeader.parse("# Task: x\nstage: planning\nstage_complete: true\n").stageComplete)
        assertFalse(TaskHeader.parse("# Task: x\nstage: planning\nstage_complete: false\n").stageComplete)
        // Missing field (old file) → false.
        assertFalse(TaskHeader.parse("# Task: x\nstage: planning\n").stageComplete)
        // Case-insensitive.
        assertTrue(TaskHeader.parse("# Task: x\nstage_complete: TRUE\n").stageComplete)
    }

    @Test
    fun `parses stage step and expected_action from a full new-format file`() {
        val markdown = """
            # Task: secure-storage
            stage: execution
            step: design the keystore wrapper
            expected_action: detail the components

            ## Goal
            Encrypt tokens at rest

            ## Implementation
            - KeystoreWrapper
        """.trimIndent()

        val header = TaskHeader.parse(markdown)

        assertEquals(TaskState.EXECUTION, header.stage)
        assertEquals("design the keystore wrapper", header.step)
        assertEquals("detail the components", header.expectedAction)
    }

    @Test
    fun `old Day 11 file without new fields loads with empty defaults`() {
        // An old task file: only stage, no step / expected_action / new sections.
        val oldFile = """
            # Task: legacy
            stage: planning

            ## Goal
            Something

            ## Requirements
            -
        """.trimIndent()

        val header = TaskHeader.parse(oldFile)

        assertEquals(TaskState.PLANNING, header.stage)
        assertEquals("", header.step)
        assertEquals("", header.expectedAction)
    }

    @Test
    fun `missing stage line defaults to PLANNING`() {
        val header = TaskHeader.parse("# Task: x\n\n## Goal\nhi\n")
        assertEquals(TaskState.PLANNING, header.stage)
    }

    @Test
    fun `null and blank markdown yield all defaults without crashing`() {
        for (input in listOf(null, "", "   ")) {
            val header = TaskHeader.parse(input)
            assertEquals(TaskState.PLANNING, header.stage)
            assertEquals("", header.step)
            assertEquals("", header.expectedAction)
        }
    }

    @Test
    fun `an unknown stage value falls back to PLANNING`() {
        val header = TaskHeader.parse("# Task: x\nstage: bogus\n")
        assertEquals(TaskState.PLANNING, header.stage)
    }
}
