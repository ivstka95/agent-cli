package org.example.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskStateMachineTest {

    // ── Transition table ────────────────────────────────────────────────────────

    @Test
    fun `nextStage follows the forward chain and DONE is terminal`() {
        assertEquals(TaskState.EXECUTION, TaskStateMachine.nextStage(TaskState.PLANNING))
        assertEquals(TaskState.VALIDATION, TaskStateMachine.nextStage(TaskState.EXECUTION))
        assertEquals(TaskState.DONE, TaskStateMachine.nextStage(TaskState.VALIDATION))
        assertNull(TaskStateMachine.nextStage(TaskState.DONE))
    }

    @Test
    fun `canTransition allows forward edges and rejects skips and backward edges`() {
        assertTrue(TaskStateMachine.canTransition(TaskState.PLANNING, TaskState.EXECUTION))
        // Skip forward is not a legal edge
        assertFalse(TaskStateMachine.canTransition(TaskState.PLANNING, TaskState.VALIDATION))
        assertFalse(TaskStateMachine.canTransition(TaskState.PLANNING, TaskState.DONE))
        // Backward is Day 15, not now
        assertFalse(TaskStateMachine.canTransition(TaskState.EXECUTION, TaskState.PLANNING))
    }

    // ── Level-1 artifact readiness ──────────────────────────────────────────────

    private fun task(requirements: String = "-", decisions: String = "-", implementation: String = "-", validation: String = "-") = """
        # Task: demo
        stage: planning
        step:
        expected_action:

        ## Goal
        Encrypt tokens

        ## Requirements
        $requirements

        ## Decisions
        $decisions

        ## Implementation
        $implementation

        ## Validation
        $validation

        ## Done
        -

        ## TODO
        -
    """.trimIndent()

    @Test
    fun `planning is ready only when Requirements AND Decisions are non-empty`() {
        assertFalse(TaskStateMachine.isArtifactReady(TaskState.PLANNING, task()))
        assertFalse(TaskStateMachine.isArtifactReady(TaskState.PLANNING, task(requirements = "- Must encrypt at rest")))
        assertTrue(
            TaskStateMachine.isArtifactReady(
                TaskState.PLANNING,
                task(requirements = "- Must encrypt at rest", decisions = "- Use AES-GCM"),
            ),
        )
    }

    @Test
    fun `execution and validation readiness check their own section`() {
        assertFalse(TaskStateMachine.isArtifactReady(TaskState.EXECUTION, task()))
        assertTrue(TaskStateMachine.isArtifactReady(TaskState.EXECUTION, task(implementation = "- KeystoreWrapper component")))

        assertFalse(TaskStateMachine.isArtifactReady(TaskState.VALIDATION, task()))
        assertTrue(TaskStateMachine.isArtifactReady(TaskState.VALIDATION, task(validation = "- Checked against all reqs")))
    }

    @Test
    fun `DONE has no required artifact and is vacuously ready`() {
        assertTrue(TaskStateMachine.isArtifactReady(TaskState.DONE, task()))
    }

    @Test
    fun `firstEmptyArtifactSection names the first missing section, in order`() {
        // Both empty → Requirements (first)
        assertEquals("Requirements", TaskStateMachine.firstEmptyArtifactSection(TaskState.PLANNING, task()))
        // Requirements filled, Decisions empty → Decisions
        assertEquals(
            "Decisions",
            TaskStateMachine.firstEmptyArtifactSection(TaskState.PLANNING, task(requirements = "- Encrypt at rest")),
        )
        // Both filled → null
        assertNull(
            TaskStateMachine.firstEmptyArtifactSection(
                TaskState.PLANNING,
                task(requirements = "- Encrypt at rest", decisions = "- AES-GCM"),
            ),
        )
        assertEquals("Implementation", TaskStateMachine.firstEmptyArtifactSection(TaskState.EXECUTION, task()))
    }
}
