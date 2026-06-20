package org.example.agent

import kotlinx.coroutines.runBlocking
import org.example.memory.MemoryStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-written fake [ResponseGenerator]: records what it received and returns a
 * canned response. Lets us assert on the assembled system prompt and the
 * working-memory overwrite without hitting the network.
 */
private class FakeResponseGenerator(
    private val canned: GeneratedResponse,
) : ResponseGenerator {
    var receivedSystemPrompt: String? = null
    var receivedCurrentTask: String? = null
    var receivedMessages: List<Message>? = null

    override suspend fun generate(
        systemPrompt: String,
        messages: List<Message>,
        currentTask: String?,
    ): GeneratedResponse {
        receivedSystemPrompt = systemPrompt
        receivedMessages = messages
        receivedCurrentTask = currentTask
        return canned
    }
}

class MemoryStoreCompositionTest {

    private val root: File = createTempDirectory("agent-mem").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    /** Seed long-term files with known markers BEFORE the store reads them. */
    private fun seedLongTerm() {
        File(root, "long-term/profiles").mkdirs()
        File(root, "long-term/profiles/seeded.md").writeText("# Profile: seeded\nPROFILE_MARKER")
        File(root, "long-term/active-profile").writeText("seeded")
        File(root, "long-term/knowledge.md").writeText("KNOWLEDGE_MARKER")
    }

    @Test
    fun `system prompt composes long-term and active task and passes current task through`() = runBlocking {
        // Given a store seeded with profile + knowledge and an active task
        seedLongTerm()
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        memory.working.overwriteActive("# Task: demo\nstage: planning\n## Goal\nTASK_MARKER\n")

        val fake = FakeResponseGenerator(GeneratedResponse("ok", taskUpdate = null, inputTokens = 1, outputTokens = 1))
        val agent = Agent(fake, memory)

        // When the agent runs
        agent.run("hi", history = emptyList())

        // Then the assembled prompt contains all three memory pieces
        val prompt = fake.receivedSystemPrompt!!
        assertTrue(prompt.contains("PROFILE_MARKER"), "profile missing from prompt")
        assertTrue(prompt.contains("KNOWLEDGE_MARKER"), "knowledge missing from prompt")
        assertTrue(prompt.contains("TASK_MARKER"), "active task missing from prompt")

        // And the active task content is passed to the generator
        assertTrue(fake.receivedCurrentTask!!.contains("TASK_MARKER"))
    }

    @Test
    fun `a non-null task update overwrites the active task file and sets the flag`() = runBlocking {
        // Given an active task
        seedLongTerm()
        val memory = MemoryStore(root)
        memory.working.createTask("demo")

        // Include the CODE-owned header fields so preserving them re-asserts the same values
        // (byte-identical round-trip).
        val updated = "# Task: demo\nstage: planning\nstage_complete: false\n## Goal\nUPDATED_GOAL\n"
        val fake = FakeResponseGenerator(GeneratedResponse("done", taskUpdate = updated, inputTokens = 4, outputTokens = 6))
        val agent = Agent(fake, memory)

        // When the agent runs and the generator returns a task update
        val response = agent.run("set the goal", history = emptyList())

        // Then the active task file is overwritten and the flag is set
        assertTrue(response.taskUpdated)
        assertEquals(updated, memory.working.activeTaskContent())
        assertEquals(4, response.inputTokens)
        assertEquals(6, response.outputTokens)
    }

    @Test
    fun `a null task update leaves the active task untouched`() = runBlocking {
        // Given an active task with known content
        seedLongTerm()
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        val before = memory.working.activeTaskContent()

        val fake = FakeResponseGenerator(GeneratedResponse("just chatting", taskUpdate = null, inputTokens = 1, outputTokens = 1))
        val agent = Agent(fake, memory)

        // When the agent runs and there is no task update
        val response = agent.run("hello", history = emptyList())

        // Then the task is unchanged and the flag is false
        assertFalse(response.taskUpdated)
        assertEquals(before, memory.working.activeTaskContent())
    }

    @Test
    fun `switching the active profile changes which profile content is injected`() = runBlocking {
        // Given two profiles with distinct markers
        seedLongTerm()
        val memory = MemoryStore(root)
        memory.longTerm.createProfile("concise")
        memory.longTerm.setProfileField("style", "CONCISE_MARKER")
        memory.longTerm.createProfile("detailed")
        memory.longTerm.setProfileField("style", "DETAILED_MARKER")

        val fake = FakeResponseGenerator(GeneratedResponse("ok", taskUpdate = null, inputTokens = 1, outputTokens = 1))
        val agent = Agent(fake, memory)

        // When the active profile is "detailed"
        memory.longTerm.switchActiveProfile("detailed")
        agent.run("hi", history = emptyList())
        // Then the detailed profile is injected, not the concise one
        assertTrue(fake.receivedSystemPrompt!!.contains("DETAILED_MARKER"))
        assertFalse(fake.receivedSystemPrompt!!.contains("CONCISE_MARKER"))

        // When switching the active profile to "concise"
        memory.longTerm.switchActiveProfile("concise")
        agent.run("hi again", history = emptyList())
        // Then the injected profile content changes accordingly
        assertTrue(fake.receivedSystemPrompt!!.contains("CONCISE_MARKER"))
        assertFalse(fake.receivedSystemPrompt!!.contains("DETAILED_MARKER"))
    }

    @Test
    fun `the active task's stage selects the injected stage prompt and changing it changes the fragment`() = runBlocking {
        // Given an active task (default stage: planning)
        seedLongTerm()
        val memory = MemoryStore(root)
        memory.working.createTask("demo")

        val fake = FakeResponseGenerator(GeneratedResponse("ok", taskUpdate = null, inputTokens = 1, outputTokens = 1))
        val agent = Agent(fake, memory)

        // When the agent runs at the planning stage
        agent.run("hi", history = emptyList())
        // Then the PLANNING fragment is injected (and not the EXECUTION one)
        assertTrue(fake.receivedSystemPrompt!!.contains("# Current stage: planning"))
        assertTrue(fake.receivedSystemPrompt!!.contains("PLANNING stage"))
        assertFalse(fake.receivedSystemPrompt!!.contains("EXECUTION stage"))

        // When the stage is switched to execution
        memory.working.setActiveStage("execution")
        agent.run("carry on", history = emptyList())
        // Then the injected fragment changes accordingly
        assertTrue(fake.receivedSystemPrompt!!.contains("# Current stage: execution"))
        assertTrue(fake.receivedSystemPrompt!!.contains("EXECUTION stage"))
        assertFalse(fake.receivedSystemPrompt!!.contains("PLANNING stage"))
    }

    @Test
    fun `with no active task, current task is null and no prompt task section`() = runBlocking {
        // Given a store with no tasks
        seedLongTerm()
        val memory = MemoryStore(root)

        val fake = FakeResponseGenerator(GeneratedResponse("hi", taskUpdate = null, inputTokens = 1, outputTokens = 1))
        val agent = Agent(fake, memory)

        // When the agent runs
        agent.run("hello", history = emptyList())

        // Then no active task is passed and the prompt has no active-task section
        assertNull(fake.receivedCurrentTask)
        assertFalse(fake.receivedSystemPrompt!!.contains("# Active task"))
    }
}
