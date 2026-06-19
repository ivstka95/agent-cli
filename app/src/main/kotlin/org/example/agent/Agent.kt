package org.example.agent

/**
 * Core agent: encapsulates the request -> response cycle.
 *
 * Depends only on the [LlmClient] interface. It does NOT own or persist history —
 * the REPL holds the in-memory session history and passes it in on every call.
 */
class Agent(private val llmClient: LlmClient) {

    suspend fun run(userInput: String, history: List<Message>): AgentResponse {
        val systemPrompt = buildSystemPrompt()
        val fullMessages = history + Message(Role.USER, userInput)

        val result = llmClient.complete(systemPrompt, fullMessages)

        return AgentResponse(
            replyText = result.replyText,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SINGLE SYSTEM-PROMPT ASSEMBLY POINT
    //
    // This is the one place where the final system prompt is built. Days 11–14
    // compose it here from layers, in this priority order:
    //   invariants (must never be violated)
    //   + long-term memory (profile + global habits)
    //   + working memory (current task context)
    //   + current stage prompt (planning / execution / ...)
    // Short-term memory (history) goes into the messages array, not here.
    //
    // For the skeleton it just returns a default. Do not scatter system-prompt
    // construction elsewhere — everything plugs in at this function.
    // ──────────────────────────────────────────────────────────────────────────
    private fun buildSystemPrompt(): String {
        return "You are a helpful assistant."
    }
}
