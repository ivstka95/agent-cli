package org.example.agent

import org.example.memory.MemoryStore

/**
 * Core agent: encapsulates the request -> response cycle.
 *
 * Depends on a [ResponseGenerator] (how the reply + task update are produced)
 * and a [MemoryStore] (the layers composed into the system prompt, and the sink
 * for the auto-extracted task). It does NOT own session history — the REPL holds
 * short-term memory and passes it in on every call.
 */
class Agent(
    private val responseGenerator: ResponseGenerator,
    private val memory: MemoryStore,
) {

    suspend fun run(userInput: String, history: List<Message>): AgentResponse {
        val activeTask = memory.working.activeTaskContent()
        val systemPrompt = buildSystemPrompt(activeTask)
        val fullMessages = history + Message(Role.USER, userInput)

        val generated = responseGenerator.generate(systemPrompt, fullMessages, activeTask)

        // Auto-extraction (working memory only): if the model returned an updated
        // task AND there is an active task, overwrite the active task file.
        var taskUpdated = false
        if (generated.taskUpdate != null && activeTask != null) {
            memory.working.overwriteActive(generated.taskUpdate)
            taskUpdated = true
        }

        return AgentResponse(
            replyText = generated.reply,
            inputTokens = generated.inputTokens,
            outputTokens = generated.outputTokens,
            taskUpdated = taskUpdated,
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SINGLE SYSTEM-PROMPT ASSEMBLY POINT
    //
    // The one place where the final system prompt is built. Days 11–15 compose it
    // here from layers, in this priority order:
    //   [Day 14] invariants (must never be violated)        — not implemented yet
    //   [Day 12+11] long-term memory (active profile + knowledge) — below
    //   [Day 11]    working memory (active task context)     — below
    //   [Day 13]    current stage prompt                     — not implemented yet
    // Short-term memory (history) goes into the messages array, not here.
    //
    // Do not scatter system-prompt construction elsewhere — everything plugs in
    // at this function.
    // ──────────────────────────────────────────────────────────────────────────
    private fun buildSystemPrompt(activeTask: String?): String = buildString {
        // [Day 14] Invariants would be prepended here, with highest priority.

        // [Day 12] Personalization: the ACTIVE user profile (switchable via the
        // :profile-* commands), injected automatically into every request.
        // [Day 11] plus global knowledge. Days 13–14 (stage prompt, invariants)
        // still plug into this same assembly point.
        appendLine("# User profile")
        appendLine(memory.longTerm.profile().trim())
        appendLine()
        appendLine("# Global knowledge")
        appendLine(memory.longTerm.knowledge().trim())
        appendLine()

        // [Day 11] Working memory: the active task context.
        if (activeTask != null) {
            appendLine("# Active task")
            appendLine(activeTask.trim())
            appendLine()
        }

        // [Day 13] The current stage prompt would be appended here.

        append(BASE_INSTRUCTION)
    }

    private companion object {
        const val BASE_INSTRUCTION =
            "You are a helpful CLI assistant. Use the profile, knowledge, and active task above as context for your reply."
    }
}
