package org.example.agent

/**
 * What a generation produces: a natural reply, plus an optional updated task
 * markdown (auto-extraction into working memory). [taskUpdate] is null when
 * there is no active task, or when generation fell back to a plain reply.
 */
data class GeneratedResponse(
    val reply: String,
    val taskUpdate: String?,
    val inputTokens: Int,
    val outputTokens: Int,
)

/**
 * Produces the assistant's response for one turn.
 *
 * ── SWAP HOOK (PLAN.md) ───────────────────────────────────────────────────────
 * The Agent depends only on this interface. Day 11 ships [CombinedResponseGenerator]
 * (ONE structured call returning {reply, task_update}). A future
 * `SeparateResponseGenerator` (two calls: one for the reply, one for the task
 * update) can replace it WITHOUT touching the Agent or the REPL.
 *
 * This interface lives in `agent/` on purpose: it depends only on [LlmClient]
 * and plain strings (`currentTask` in, `taskUpdate` out) — never on the memory
 * storage classes. The Agent is what connects a generator to the MemoryStore, so
 * dependencies stay one-directional (agent -> memory, agent -> llm).
 */
interface ResponseGenerator {

    /**
     * @param systemPrompt the fully assembled system prompt (memory layers + base)
     * @param messages     session history + the new user message
     * @param currentTask  the active task's markdown, or null if no active task
     */
    suspend fun generate(
        systemPrompt: String,
        messages: List<Message>,
        currentTask: String?,
    ): GeneratedResponse
}
