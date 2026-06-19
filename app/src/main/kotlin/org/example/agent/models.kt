package org.example.agent

/** Raw result returned by the LLM client (transport layer). */
data class LlmResult(
    val replyText: String,
    val inputTokens: Int,
    val outputTokens: Int,
)

/** Result the Agent returns to its caller (the REPL). */
data class AgentResponse(
    val replyText: String,
    val inputTokens: Int,
    val outputTokens: Int,
)
