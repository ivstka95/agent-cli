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

    /**
     * One tool-use round-trip (Day 17): sends [tools] with `tool_choice` auto so the model may call
     * a tool OR answer. [exchanges] are the accumulated tool_use/tool_result pairs from this loop,
     * re-serialized as native content blocks each call. Returns an [LlmTurn]: a final [LlmTurn.Answer]
     * or the [LlmTurn.ToolRequests] the caller must execute and feed back.
     *
     * Distinct from [completeStructured]'s FORCED tool_choice — the two modes are never mixed.
     * Has a default so non-agentic [LlmClient] fakes need not implement it.
     */
    suspend fun runToolTurn(
        systemPrompt: String,
        messages: List<Message>,
        exchanges: List<ToolExchange>,
        tools: List<ToolSpec>,
    ): LlmTurn = throw UnsupportedOperationException("runToolTurn is not supported by this LlmClient")
}
