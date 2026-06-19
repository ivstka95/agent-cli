package org.example.agent

import kotlinx.serialization.json.JsonObject

/**
 * Abstraction over an LLM provider. The Agent and the response generators depend
 * ONLY on this interface, never on a concrete client — keeps the domain free of
 * transport details.
 */
interface LlmClient {

    /** Plain text completion. Returns the assistant's reply text + token usage. */
    suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult

    /**
     * Structured completion via forced tool-use: the model is required to call a
     * single tool named [toolName] whose input matches [inputSchema] (a JSON
     * Schema). The tool input is returned verbatim as a JSON string in
     * [StructuredResult.toolInputJson] for the caller to parse.
     *
     * This is the channel used for the Day 11 combined `{reply, task_update}`
     * call; the system prompt is still sent as the top-level `system` field.
     */
    suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult
}
