package org.example.service

import kotlinx.serialization.json.JsonObject
import org.example.agent.LlmClient
import org.example.agent.LlmResult
import org.example.agent.Message
import org.example.agent.StructuredResult

/**
 * Test double for the Day-30 service tests: records the system prompt + messages it was asked to
 * complete (so tests can assert history accrual / trimming / input truncation) and returns a canned
 * reply. Set [fail] to make [complete] throw, exercising the Ollama-down → [ChatOutcome.LlmError] path.
 * Only [complete] is used by the service; [completeStructured] returns a trivial payload.
 */
internal class FakeLlmClient(
    private val reply: String = "ok",
    private val fail: Boolean = false,
) : LlmClient {
    var lastSystemPrompt: String? = null
    var lastMessages: List<Message> = emptyList()

    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult {
        if (fail) throw RuntimeException("Ollama request failed — is it running?")
        lastSystemPrompt = systemPrompt
        lastMessages = messages
        return LlmResult(reply, inputTokens = 1, outputTokens = 1)
    }

    override suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult = StructuredResult("{}", inputTokens = 0, outputTokens = 0)
}
