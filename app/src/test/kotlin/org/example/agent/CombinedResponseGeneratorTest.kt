package org.example.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-written fake LLM client (no mocking library). Records which path was
 * exercised and returns canned results.
 */
private class FakeLlmClient(
    private val cannedReply: LlmResult = LlmResult("plain reply", 1, 1),
    private val cannedToolInputJson: String = "{}",
) : LlmClient {
    var completeCalled = false
    var completeStructuredCalled = false

    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult {
        completeCalled = true
        return cannedReply
    }

    override suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult {
        completeStructuredCalled = true
        return StructuredResult(toolInputJson = cannedToolInputJson, inputTokens = 5, outputTokens = 9)
    }
}

class CombinedResponseGeneratorTest {

    @Test
    fun `with an active task, valid tool JSON is parsed into reply and task update`() = runBlocking {
        // Given a structured call returning valid {reply, task_update}
        val json = """{"reply":"Got it.","task_update":"# Task: t\nstage: planning\n## Goal\nShip\n"}"""
        val fake = FakeLlmClient(cannedToolInputJson = json)
        val generator = CombinedResponseGenerator(fake)

        // When generating with an active task
        val result = generator.generate("system", listOf(Message(Role.USER, "hi")), currentTask = "# Task: t")

        // Then the structured path is used and both fields are parsed
        assertTrue(fake.completeStructuredCalled)
        assertFalse(fake.completeCalled)
        assertEquals("Got it.", result.reply)
        assertTrue(result.taskUpdate!!.contains("## Goal"))
        assertEquals(5, result.inputTokens)
        assertEquals(9, result.outputTokens)
    }

    @Test
    fun `malformed tool JSON falls back to raw reply and no task update`() = runBlocking {
        // Given a structured call returning broken/truncated JSON
        val broken = """{"reply":"oops","task_upda"""
        val fake = FakeLlmClient(cannedToolInputJson = broken)
        val generator = CombinedResponseGenerator(fake)

        // When generating with an active task
        val result = generator.generate("system", listOf(Message(Role.USER, "hi")), currentTask = "# Task: t")

        // Then it does not crash: the raw text is the reply and the task is untouched
        assertEquals(broken, result.reply)
        assertNull(result.taskUpdate)
    }

    @Test
    fun `with no active task, the plain complete path is used`() = runBlocking {
        // Given no active task
        val fake = FakeLlmClient(cannedReply = LlmResult("hello", 2, 3))
        val generator = CombinedResponseGenerator(fake)

        // When generating
        val result = generator.generate("system", listOf(Message(Role.USER, "hi")), currentTask = null)

        // Then the plain path is used and there is no task update
        assertTrue(fake.completeCalled)
        assertFalse(fake.completeStructuredCalled)
        assertEquals("hello", result.reply)
        assertNull(result.taskUpdate)
        assertEquals(2, result.inputTokens)
        assertEquals(3, result.outputTokens)
    }
}
