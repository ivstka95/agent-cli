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

/**
 * Scripted structured fake: returns a queue of [StructuredResult]s (clamped to the
 * last once exhausted), counts `completeStructured` calls, and records the system
 * prompts it saw — so we can assert the exact retry behavior.
 */
private class ScriptedLlmClient(
    private val structuredResults: List<StructuredResult>,
) : LlmClient {
    var structuredCalls = 0
        private set
    val systemPrompts = mutableListOf<String>()

    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult =
        LlmResult("plain reply", 1, 1)

    override suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult {
        systemPrompts += systemPrompt
        val result = structuredResults[minOf(structuredCalls, structuredResults.lastIndex)]
        structuredCalls++
        return result
    }
}

class CombinedResponseGeneratorTest {

    // task_update is required (no default), so this decodes but is incomplete → parse failure.
    private val textJson = """{"reply":"I reviewed it","stage_complete":false}"""
    private val validJson =
        """{"reply":"Reviewed","task_update":"# Task: t\nstage: validation\n## Validation\n- Found a gap\n","stage_complete":true}"""

    @Test
    fun `a parse failure triggers exactly one retry and uses the retry result`() = runBlocking {
        val fake = ScriptedLlmClient(
            listOf(
                StructuredResult(toolInputJson = textJson, inputTokens = 5, outputTokens = 9),
                StructuredResult(toolInputJson = validJson, inputTokens = 7, outputTokens = 11),
            ),
        )
        val generator = CombinedResponseGenerator(fake)

        val result = generator.generate("system", listOf(Message(Role.USER, "review")), currentTask = "# Task: t")

        // Exactly one retry (two calls total) and the result comes from the retry.
        assertEquals(2, fake.structuredCalls)
        assertEquals("Reviewed", result.reply)
        assertTrue(result.taskUpdate!!.contains("## Validation"))
        assertTrue(result.stageComplete)
        // Tokens summed across both calls.
        assertEquals(12, result.inputTokens)
        assertEquals(20, result.outputTokens)
        // The retry carried the reinforced format reminder; the first did not.
        assertFalse(fake.systemPrompts[0].contains("not in the required structured format"))
        assertTrue(fake.systemPrompts[1].contains("not in the required structured format"))
    }

    @Test
    fun `two parse failures stop after one retry and fall back`() = runBlocking {
        val fake = ScriptedLlmClient(
            listOf(
                StructuredResult(toolInputJson = textJson, inputTokens = 5, outputTokens = 9),
                StructuredResult(toolInputJson = """still not structured""", inputTokens = 7, outputTokens = 11),
            ),
        )
        val generator = CombinedResponseGenerator(fake)

        val result = generator.generate("system", listOf(Message(Role.USER, "review")), currentTask = "# Task: t")

        // Exactly one retry — no third call — then fallback.
        assertEquals(2, fake.structuredCalls)
        assertEquals("still not structured", result.reply) // raw reply from the last attempt
        assertNull(result.taskUpdate)
        assertFalse(result.stageComplete)
        // Tokens still summed across both attempts.
        assertEquals(12, result.inputTokens)
        assertEquals(20, result.outputTokens)
    }

    @Test
    fun `a successful first structured response does not retry`() = runBlocking {
        val fake = ScriptedLlmClient(
            listOf(StructuredResult(toolInputJson = validJson, inputTokens = 7, outputTokens = 11)),
        )
        val generator = CombinedResponseGenerator(fake)

        val result = generator.generate("system", listOf(Message(Role.USER, "review")), currentTask = "# Task: t")

        assertEquals(1, fake.structuredCalls)
        assertEquals("Reviewed", result.reply)
        assertEquals(7, result.inputTokens)
        assertEquals(11, result.outputTokens)
    }

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
    fun `stage_complete is parsed from the tool JSON`() = runBlocking {
        // Given a structured call that reports the stage complete
        val json = """{"reply":"Done.","task_update":"# Task: t\nstage: planning\n","stage_complete":true}"""
        val fake = FakeLlmClient(cannedToolInputJson = json)
        val generator = CombinedResponseGenerator(fake)

        // When generating with an active task
        val result = generator.generate("system", listOf(Message(Role.USER, "hi")), currentTask = "# Task: t")

        // Then stageComplete reflects the model's judgment
        assertTrue(result.stageComplete)
    }

    @Test
    fun `malformed tool JSON falls back to raw reply, no task update, stage not complete`() = runBlocking {
        // Given a structured call returning broken/truncated JSON
        val broken = """{"reply":"oops","task_upda"""
        val fake = FakeLlmClient(cannedToolInputJson = broken)
        val generator = CombinedResponseGenerator(fake)

        // When generating with an active task
        val result = generator.generate("system", listOf(Message(Role.USER, "hi")), currentTask = "# Task: t")

        // Then it does not crash: the raw text is the reply, the task is untouched,
        // and the stage is not reported complete (so no transition can fire)
        assertEquals(broken, result.reply)
        assertNull(result.taskUpdate)
        assertFalse(result.stageComplete)
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
