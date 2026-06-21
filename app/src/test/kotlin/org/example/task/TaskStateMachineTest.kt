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
    fun `canTransition allows forward edges`() {
        assertTrue(TaskStateMachine.canTransition(TaskState.PLANNING, TaskState.EXECUTION))
        assertTrue(TaskStateMachine.canTransition(TaskState.EXECUTION, TaskState.VALIDATION))
        assertTrue(TaskStateMachine.canTransition(TaskState.VALIDATION, TaskState.DONE))
    }

    @Test
    fun `canTransition allows backward rework edges (Day 15)`() {
        assertTrue(TaskStateMachine.canTransition(TaskState.VALIDATION, TaskState.EXECUTION))
        assertTrue(TaskStateMachine.canTransition(TaskState.VALIDATION, TaskState.PLANNING))
        assertTrue(TaskStateMachine.canTransition(TaskState.EXECUTION, TaskState.PLANNING))
    }

    @Test
    fun `canTransition rejects skips - the absence of an edge enforces no-skipping`() {
        assertFalse(TaskStateMachine.canTransition(TaskState.PLANNING, TaskState.VALIDATION))
        assertFalse(TaskStateMachine.canTransition(TaskState.PLANNING, TaskState.DONE))
        assertFalse(TaskStateMachine.canTransition(TaskState.EXECUTION, TaskState.DONE))
        // DONE is terminal — no outgoing edges at all.
        assertFalse(TaskStateMachine.canTransition(TaskState.DONE, TaskState.PLANNING))
    }

    @Test
    fun `allowedTargets lists the forward successor first, then backward rework targets`() {
        assertEquals(listOf(TaskState.EXECUTION), TaskStateMachine.allowedTargets(TaskState.PLANNING))
        assertEquals(
            listOf(TaskState.VALIDATION, TaskState.PLANNING),
            TaskStateMachine.allowedTargets(TaskState.EXECUTION),
        )
        assertEquals(
            listOf(TaskState.DONE, TaskState.EXECUTION, TaskState.PLANNING),
            TaskStateMachine.allowedTargets(TaskState.VALIDATION),
        )
        assertEquals(emptyList(), TaskStateMachine.allowedTargets(TaskState.DONE))
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
