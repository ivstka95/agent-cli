package org.example.memory

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkingMemoryTest {

    private val root: File = createTempDirectory("working-mem").toFile()
    private val workingDir = File(root, "working")

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `createTask writes the strict template and sets it active`() {
        // Given a working memory over a fresh dir
        val wm = WorkingMemory(workingDir)

        // When a task is created
        wm.createTask("alpha")

        // Then it becomes active and the file has the strict structure
        assertEquals("alpha", wm.activeTaskName())
        val content = wm.activeTaskContent()!!
        assertTrue(content.startsWith("# Task: alpha"))
        assertTrue(content.contains("stage: planning"))
        assertTrue(content.contains("## Goal"))
        assertTrue(content.contains("## Requirements"))
        assertTrue(content.contains("## Decisions"))
        assertTrue(content.contains("## Done"))
        assertTrue(content.contains("## TODO"))
    }

    @Test
    fun `creating a second task moves the active pointer`() {
        // Given a working memory with one task
        val wm = WorkingMemory(workingDir)
        wm.createTask("alpha")

        // When a second task is created
        wm.createTask("beta")

        // Then the newest is active
        assertEquals("beta", wm.activeTaskName())
    }

    @Test
    fun `switchActive moves the pointer and rejects an unknown task`() {
        // Given two tasks
        val wm = WorkingMemory(workingDir)
        wm.createTask("alpha")
        wm.createTask("beta")

        // When switching back to an existing task
        assertTrue(wm.switchActive("alpha"))
        assertEquals("alpha", wm.activeTaskName())

        // When switching to a non-existent task
        assertFalse(wm.switchActive("ghost"))
        // Then the active pointer is unchanged
        assertEquals("alpha", wm.activeTaskName())
    }

    @Test
    fun `overwriteActive replaces the active task content`() {
        // Given an active task
        val wm = WorkingMemory(workingDir)
        wm.createTask("alpha")

        // When the active task is overwritten
        val updated = "# Task: alpha\nstage: planning\n\n## Goal\nShip Day 11\n"
        wm.overwriteActive(updated)

        // Then the active content reflects the new text
        assertEquals(updated, wm.activeTaskContent())
    }

    @Test
    fun `listTasks returns task names sorted`() {
        // Given several tasks created out of order
        val wm = WorkingMemory(workingDir)
        wm.createTask("gamma")
        wm.createTask("alpha")
        wm.createTask("beta")

        // Then listing returns them sorted
        assertEquals(listOf("alpha", "beta", "gamma"), wm.listTasks())
    }

    @Test
    fun `active task and content persist across a fresh instance (restart)`() {
        // Given a task created and made active by one instance
        WorkingMemory(workingDir).apply {
            createTask("alpha")
            overwriteActive("# Task: alpha\nstage: planning\n\n## Goal\nPersist me\n")
        }

        // When a new instance opens the same directory (simulating a restart)
        val reopened = WorkingMemory(workingDir)

        // Then the active task and its content are still there
        assertEquals("alpha", reopened.activeTaskName())
        assertTrue(reopened.activeTaskContent()!!.contains("Persist me"))
    }

    @Test
    fun `no active task yields null content`() {
        // Given an empty working memory
        val wm = WorkingMemory(workingDir)

        // Then there is no active task or content
        assertNull(wm.activeTaskName())
        assertNull(wm.activeTaskContent())
    }
}
