package org.example.agent

import org.example.task.TaskState

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

/**
 * One step of the autonomous stage chain (Day 13 / 3b): the agent's work on a
 * single stage within a user turn. [stage] is null only for the no-active-task
 * plain reply. [refinement] is set if a one-shot self-correction ran during this
 * step; [transition] is set if the stage advanced after this step.
 */
data class ChainStep(
    val stage: TaskState?,
    val reply: String,
    val refinement: Refinement? = null,
    val transition: StageTransition? = null,
)

/**
 * Result the Agent returns to its caller (the REPL): the ordered [steps] the
 * autonomous chain produced this turn (Day 13 / 3b), with token totals summed
 * across every call in the chain.
 */
data class AgentResponse(
    val steps: List<ChainStep>,
    val inputTokens: Int,
    val outputTokens: Int,
    /** True when any call this turn updated the active working-memory task file. */
    val taskUpdated: Boolean = false,
) {
    /**
     * The chain's replies collapsed into ONE assistant turn for session history
     * (the API needs alternating roles, so a turn's multiple stage replies can't
     * each be a separate assistant message).
     */
    val assistantText: String
        get() = steps.flatMap { listOfNotNull(it.reply, it.refinement?.replyText) }.joinToString("\n\n")
}

/**
 * A one-shot self-correction follow-up (Day 13 / 3b): the model marked the
 * [stage] complete but its artifact section was empty, so CODE asked once more
 * for concrete content. [replyText] is the follow-up reply.
 */
data class Refinement(val stage: TaskState, val replyText: String)

/** An auto-transition that happened in the chain (Day 13 / 3b): [from] → [to]. */
data class StageTransition(val from: TaskState, val to: TaskState)
