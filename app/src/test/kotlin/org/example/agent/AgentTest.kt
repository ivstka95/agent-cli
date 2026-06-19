package org.example.agent

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hand-written fake (no mocking library): records what it was called with and
 * returns a canned result.
 */
private class FakeLlmClient(
    private val canned: LlmResult,
) : LlmClient {
    var receivedSystemPrompt: String? = null
    var receivedMessages: List<Message>? = null

    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult {
        receivedSystemPrompt = systemPrompt
        receivedMessages = messages
        return canned
    }
}

class AgentTest {

    @Test
    fun `run maps the LlmResult into an AgentResponse`() = runBlocking {
        // Given an agent backed by a fake returning a known result
        val fake = FakeLlmClient(LlmResult(replyText = "hello there", inputTokens = 12, outputTokens = 7))
        val agent = Agent(fake)

        // When run is called
        val response = agent.run("hi", history = emptyList())

        // Then the response carries the fake's reply and token usage
        assertEquals("hello there", response.replyText)
        assertEquals(12, response.inputTokens)
        assertEquals(7, response.outputTokens)
    }

    @Test
    fun `run appends the new user message to the history and sends a system prompt`() = runBlocking {
        // Given an agent and a prior assistant turn in the history
        val fake = FakeLlmClient(LlmResult("ok", 1, 1))
        val agent = Agent(fake)
        val history = listOf(Message(Role.ASSISTANT, "previous reply"))

        // When run is called with new input
        agent.run("new question", history)

        // Then the client receives history + the new USER message, in order
        val sent = fake.receivedMessages!!
        assertEquals(2, sent.size)
        assertEquals(Message(Role.ASSISTANT, "previous reply"), sent[0])
        assertEquals(Message(Role.USER, "new question"), sent[1])

        // And a non-blank system prompt is assembled
        assertTrue(fake.receivedSystemPrompt!!.isNotBlank())
    }
}
