package org.example.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.example.mcp.McpClient
import org.example.mcp.textOrError

/**
 * Claude-Code-style agentic tool-use loop — the NEW layer ABOVE the single round-trip
 * ([LlmClient.runToolTurn]). It runs ONLY on the conversational path: one LLM step, and if the model
 * requests tools, it executes them via [mcpClient], feeds the results back, and repeats until the
 * model returns a final text answer (or the [maxIterations] guard trips).
 *
 * The model DECIDES whether to call a tool (real native tool-use), given [tools]'s schemas.
 */
class AgenticLoop(
    private val llmClient: LlmClient,
    private val mcpClient: McpClient,
    private val tools: List<ToolSpec>,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
) {

    suspend fun run(systemPrompt: String, messages: List<Message>): AgenticResult {
        val exchanges = mutableListOf<ToolExchange>()
        var inputTokens = 0
        var outputTokens = 0

        repeat(maxIterations) {
            val turn = llmClient.runToolTurn(systemPrompt, messages, exchanges, tools)
            inputTokens += turn.inputTokens
            outputTokens += turn.outputTokens

            when (turn) {
                is LlmTurn.Answer -> return AgenticResult(turn.text, inputTokens, outputTokens)

                is LlmTurn.ToolRequests -> {
                    val results = turn.toolUses.map { use -> execute(use) }
                    exchanges += ToolExchange(turn.toolUses, results)
                }
            }
        }

        // Guard tripped: the model kept calling tools without converging.
        return AgenticResult(
            reply = "I couldn't complete this using the available tools within $maxIterations steps.",
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
    }

    /** Execute one tool call via MCP, mapping any failure to an error [ToolResult] (never throws). */
    private suspend fun execute(use: ToolUseRequest): ToolResult {
        // [Day 17] Color-highlighted transparency log (visible in the :app:run terminal). Full args.
        log("LLM requested tool: ${use.name}(${use.argsJson})")

        val outcome = runCatching {
            mcpClient.callTool(use.name, parseArgs(use.argsJson)).textOrError()
        }
        return outcome.fold(
            onSuccess = { text ->
                log("tool result received:\n$text")
                ToolResult(use.id, text, isError = false)
            },
            onFailure = { e ->
                val message = "Error calling ${use.name}: ${e.message}"
                log("tool result received (error):\n$message")
                ToolResult(use.id, message, isError = true)
            },
        )
    }

    /** [Day 17] One green, bold-prefixed `[AGENT]` line to stdout. */
    private fun log(body: String) =
        println(Ansi.bold(Ansi.green("[AGENT]")) + " " + Ansi.green(body))

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 5

        private val JSON = Json { ignoreUnknownKeys = true }

        /** Parse the model's tool-input JSON object into plain-Kotlin args for [McpClient.callTool]. */
        internal fun parseArgs(argsJson: String): Map<String, Any?> {
            val obj = runCatching { JSON.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
                ?: return emptyMap()
            return obj.mapValues { (_, value) -> value.toKotlin() }
        }

        private fun JsonElement.toKotlin(): Any? = when (this) {
            is JsonNull -> null
            is JsonPrimitive -> when {
                isString -> content
                else -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
            }
            is JsonObject -> mapValues { it.value.toKotlin() }
            is JsonArray -> map { it.toKotlin() }
        }
    }
}
