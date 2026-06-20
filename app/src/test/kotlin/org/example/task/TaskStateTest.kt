package org.example.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskStateTest {

    @Test
    fun `stageValue and parse round-trip for every stage`() {
        // Given each stage, When serialized then strictly parsed, Then it round-trips
        for (stage in TaskState.entries) {
            assertEquals(stage, TaskState.parse(stage.stageValue))
        }
    }

    @Test
    fun `stageValue is the lowercase name`() {
        assertEquals("planning", TaskState.PLANNING.stageValue)
        assertEquals("execution", TaskState.EXECUTION.stageValue)
        assertEquals("validation", TaskState.VALIDATION.stageValue)
        assertEquals("done", TaskState.DONE.stageValue)
    }

    @Test
    fun `parse is case-insensitive and trims`() {
        assertEquals(TaskState.EXECUTION, TaskState.parse("  Execution "))
        assertEquals(TaskState.DONE, TaskState.parse("DONE"))
    }

    @Test
    fun `parse returns null for invalid or empty input`() {
        assertNull(TaskState.parse("planing"))
        assertNull(TaskState.parse(""))
        assertNull(TaskState.parse("   "))
        assertNull(TaskState.parse(null))
    }

    @Test
    fun `fromStageField defaults to PLANNING for invalid or missing values`() {
        assertEquals(TaskState.PLANNING, TaskState.fromStageField(null))
        assertEquals(TaskState.PLANNING, TaskState.fromStageField(""))
        assertEquals(TaskState.PLANNING, TaskState.fromStageField("nonsense"))
        // But a valid value is still honoured
        assertEquals(TaskState.VALIDATION, TaskState.fromStageField("validation"))
    }
}
