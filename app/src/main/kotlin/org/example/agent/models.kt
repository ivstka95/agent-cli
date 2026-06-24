package org.example.agent

import kotlinx.serialization.json.JsonObject
import org.example.task.TaskState

/** Raw result of a plain text completion (transport layer). */
data class LlmResult(
    val replyText: String,
    val inputTokens: Int,
    val outputTokens: Int,
)

// ── Day 17: native tool-use types (conversational agentic loop) ─────────────────

/**
 * An available tool, in the LLM provider's terms: a name, a description, and a JSON-Schema
 * [inputSchema]. Built from the MCP server's advertised tools by `McpToolAdapter`.
 */
data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)

/**
 * One tool the model asked to call this turn. [argsJson] is the model's tool input as a raw JSON
 * object string (the loop parses it into arguments for the MCP call; the client re-serializes it
 * unchanged when echoing the assistant `tool_use` block back).
 */
data class ToolUseRequest(val id: String, val name: String, val argsJson: String)

/** The result of executing one [ToolUseRequest], fed back to the model as a `tool_result` block. */
data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean)

/** One round-trip of the agentic loop: the model's [uses] and the matching tool [results]. */
data class ToolExchange(val uses: List<ToolUseRequest>, val results: List<ToolResult>)

/**
 * Outcome of ONE tool-use round-trip ([LlmClient.runToolTurn]): either a final text [Answer] (no
 * tool requested) or a set of [ToolRequests] the loop must execute and feed back.
 */
sealed interface LlmTurn {
    val inputTokens: Int
    val outputTokens: Int

    data class Answer(
        val text: String,
        override val inputTokens: Int,
        override val outputTokens: Int,
    ) : LlmTurn

    data class ToolRequests(
        val toolUses: List<ToolUseRequest>,
        override val inputTokens: Int,
        override val outputTokens: Int,
    ) : LlmTurn
}

/** Final result of the agentic loop: the assistant's reply plus summed token usage. */
data class AgenticResult(val reply: String, val inputTokens: Int, val outputTokens: Int)

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
 * One step of the stage chain (Day 13): the agent's work on a single stage within
 * a user turn. [stage] is null only for the no-active-task plain reply.
 * [refinement] is set if a one-shot self-correction ran during this step.
 * [transition] is set if the stage actually advanced (AUTO mode); [pendingTransition]
 * is set instead when the stage is complete + ready but the advance is DEFERRED for
 * the user's `:next` (CONFIRM mode, 3c). At most one of the two is non-null.
 */
data class ChainStep(
    val stage: TaskState?,
    val reply: String,
    val refinement: Refinement? = null,
    val transition: StageTransition? = null,
    val pendingTransition: StageTransition? = null,
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
