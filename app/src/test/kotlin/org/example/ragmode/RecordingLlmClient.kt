package org.example.ragmode

import kotlinx.serialization.json.JsonObject
import org.example.agent.LlmClient
import org.example.agent.LlmResult
import org.example.agent.Message
import org.example.agent.StructuredResult

/**
 * Shared test double for the RAG-mode tests: records the system prompt + messages it was asked to
 * complete and returns a canned [reply]. Set [fail] to make [complete] throw, exercising callers'
 * fallback/error paths (e.g. [LlmQueryRewriter]'s rewrite-failure fallback).
 */
internal class RecordingLlmClient(
    private val reply: String = "the answer",
    private val fail: Boolean = false,
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
    ): StructuredResult = throw UnsupportedOperationException()
}
