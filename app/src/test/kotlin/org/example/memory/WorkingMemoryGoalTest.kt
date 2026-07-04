package org.example.memory

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Day 25] The dialogue Goal is task memory: once set it must survive 10–15 turns of model-driven
 * task rewrites so the assistant never loses the goal. `overwriteActivePreservingHeader` protects a
 * set Goal like the CODE-owned header fields, while an empty Goal stays model-fillable (planning).
 */
class WorkingMemoryGoalTest {

    private val root: File = createTempDirectory("wm-goal").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun taskBody(goal: String, requirements: String = "-"): String =
        "# Task: demo\nstage: planning\nstage_complete: false\n\n" +
            "## Goal\n$goal\n\n" +
            "## Requirements\n$requirements\n\n" +
            "## Decisions\n-\n"

    @Test
    fun `a set Goal survives a model rewrite that would drift it, while other sections still update`() {
        val wm = WorkingMemory(root)
        wm.createTask("demo")
        wm.overwriteActive(taskBody(goal = "LOCKED_GOAL"))

        // The model returns an update that changes the Goal AND adds a requirement.
        wm.overwriteActivePreservingHeader(taskBody(goal = "DRIFTED_GOAL", requirements = "REQ_ADDED"))

        val content = wm.activeTaskContent()!!
        assertTrue(content.contains("LOCKED_GOAL"), "the set Goal must be preserved")
        assertFalse(content.contains("DRIFTED_GOAL"), "a drifted Goal must be rejected")
        assertTrue(content.contains("REQ_ADDED"), "non-Goal sections must still update")
    }

    @Test
    fun `setActiveGoal sets the goal, activeGoal reads it back, and a later rewrite cannot erase it`() {
        val wm = WorkingMemory(root)
        wm.createTask("demo")
        assertTrue(wm.activeGoal().isEmpty(), "the template starts with a blank goal")

        wm.setActiveGoal("MY_GOAL")
        assertEquals("MY_GOAL", wm.activeGoal())

        // A later model rewrite that omits the Goal cannot erase the set goal.
        wm.overwriteActivePreservingHeader(taskBody(goal = ""))
        assertEquals("MY_GOAL", wm.activeGoal())
    }

    @Test
    fun `an empty Goal is still fillable by a model update`() {
        val wm = WorkingMemory(root)
        wm.createTask("demo") // the template's Goal section starts blank

        wm.overwriteActivePreservingHeader(taskBody(goal = "NEW_GOAL"))

        assertTrue(wm.activeTaskContent()!!.contains("NEW_GOAL"))
    }
}
