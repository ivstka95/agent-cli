package org.example.agent

/** Raw result of a plain text completion (transport layer). */
data class LlmResult(
    val replyText: String,
    val inputTokens: Int,
    val outputTokens: Int,
)

/**
 * Raw result of a structured (forced tool-use) completion (transport layer).
 * [toolInputJson] is the model's tool input serialized back to a JSON string;
 * the caller parses it. On parse failure the caller falls back to a plain reply.
 */
data class StructuredResult(
    val toolInputJson: String,
    val inputTokens: Int,
    val outputTokens: Int,
)

/** Result the Agent returns to its caller (the REPL). */
data class AgentResponse(
    val replyText: String,
    val inputTokens: Int,
    val outputTokens: Int,
    /** True when this turn updated the active working-memory task file. */
    val taskUpdated: Boolean = false,
)
