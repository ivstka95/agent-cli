package org.example.ragmode

import kotlinx.serialization.json.JsonObject
import org.example.agent.LlmClient
import org.example.agent.LlmResult
import org.example.agent.Message
import org.example.agent.StructuredResult

/**
 * Shared test double for the RAG-mode tests: records the system prompt + messages it was asked to
 * complete and returns a canned reply. [complete] returns [reply]; [completeStructured] returns
 * [structuredJson] as the tool payload (defaulting to a minimal valid `{answer, citations, dont_know}`
 * built from [reply]) — pass a hand-written payload to exercise citations / dont_know / a parse-failure
 * fallback (any non-JSON string). Set [fail] to make BOTH calls throw, exercising callers' error paths.
 */
internal class RecordingLlmClient(
    private val reply: String = "the answer",
    private val fail: Boolean = false,
    private val structuredJson: String? = null,
) : LlmClient {
    var systemPrompt: String? = null
    var messages: List<Message> = emptyList()

    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult {
        if (fail) throw RuntimeException("boom")
        this.systemPrompt = systemPrompt
        this.messages = messages
        return LlmResult(reply, inputTokens = 7, outputTokens = 3)
    }

    override suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult {
        if (fail) throw RuntimeException("boom")
        this.systemPrompt = systemPrompt
        this.messages = messages
        val payload = structuredJson ?: """{"answer":"$reply","citations":[],"dont_know":false}"""
        return StructuredResult(payload, inputTokens = 7, outputTokens = 3)
    }
}
