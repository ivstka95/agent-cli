package org.example.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Day 11's default [ResponseGenerator]: ONE structured (forced tool-use) call
 * that returns BOTH a natural reply and the full updated task markdown.
 *
 * - No active task -> a plain [LlmClient.complete] call; taskUpdate = null.
 * - Active task    -> [LlmClient.completeStructured] forcing a {reply, task_update}
 *   tool. The model receives the current task (already in the system prompt) plus
 *   a merge instruction, and returns the FULL updated task markdown.
 *
 * On ANY structured-output failure (parse error, truncated JSON, missing fields)
 * we FALL BACK to showing the raw reply and NOT touching the task — never crash.
 * [STRUCTURED_MAX_TOKENS] in the client keeps the JSON from being truncated.
 */
class CombinedResponseGenerator(private val llmClient: LlmClient) : ResponseGenerator {

    override suspend fun generate(
        systemPrompt: String,
        messages: List<Message>,
        currentTask: String?,
    ): GeneratedResponse {
        // No active task: nothing to extract — a plain reply is enough (and cheaper).
        if (currentTask == null) {
            val result = llmClient.complete(systemPrompt, messages)
            return GeneratedResponse(
                reply = result.replyText,
                taskUpdate = null,
                inputTokens = result.inputTokens,
                outputTokens = result.outputTokens,
            )
        }

        val structured = llmClient.completeStructured(
            systemPrompt = systemPrompt,
            messages = messages,
            toolName = TOOL_NAME,
            toolDescription = TOOL_DESCRIPTION,
            inputSchema = OUTPUT_SCHEMA,
        )

        val parsed = runCatching { JSON.decodeFromString<CombinedOutput>(structured.toolInputJson) }
            .getOrNull()

        return if (parsed != null) {
            GeneratedResponse(
                reply = parsed.reply,
                taskUpdate = parsed.taskUpdate,
                inputTokens = structured.inputTokens,
                outputTokens = structured.outputTokens,
            )
        } else {
            // Fallback: show whatever we got, leave the task untouched.
            GeneratedResponse(
                reply = structured.toolInputJson,
                taskUpdate = null,
                inputTokens = structured.inputTokens,
                outputTokens = structured.outputTokens,
            )
        }
    }

    @Serializable
    private data class CombinedOutput(
        val reply: String,
        @SerialName("task_update") val taskUpdate: String,
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        const val TOOL_NAME = "respond"

        // How to use the tool lives in the tool description (not appended to the
        // system prompt — Agent.buildSystemPrompt stays the single assembly point).
        // It references the current task's structure rather than re-listing the
        // sections, so WorkingMemory's task template remains the single source of
        // truth for the task format.
        val TOOL_DESCRIPTION = """
            Reply to the user and update the current task file. Provide two fields:
            - reply: your natural-language answer to the user.
            - task_update: the FULL task file markdown, preserving the exact structure
              of the current task shown in the system prompt. Keep ALL header fields
              (stage, step, expected_action) and ALL sections (## Goal, ## Requirements,
              ## Decisions, ## Implementation, ## Validation, ## Done, ## TODO). Merge
              what this exchange produced into the sections that the CURRENT stage is
              responsible for — planning fills Requirements + Decisions, execution fills
              Implementation, validation fills Validation — and keep step and
              expected_action current for the work in progress. Do not change the stage
              field yourself. If nothing about the task changed, return it unchanged. Do
              not invent content.
        """.trimIndent()

        /** JSON Schema for the {reply, task_update} tool input (strict). */
        val OUTPUT_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("reply") {
                    put("type", "string")
                    put("description", "Natural-language reply to the user.")
                }
                putJsonObject("task_update") {
                    put("type", "string")
                    put("description", "The full updated task file markdown.")
                }
            }
            putJsonArray("required") {
                add("reply")
                add("task_update")
            }
        }
    }
}
