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

        // First structured attempt.
        val first = completeStructured(systemPrompt, messages)
        parse(first.toolInputJson)?.let { return toGenerated(it, first.inputTokens, first.outputTokens) }

        // Parse failed (the model didn't return a usable tool_use result — e.g. it wrote
        // plain-text / text-JSON in the message body). Make EXACTLY ONE retry with a
        // reinforced format reminder. Straight-line — at most 2 calls, no loop/recursion.
        val retry = completeStructured(systemPrompt + RETRY_REMINDER, messages)
        val inputTokens = first.inputTokens + retry.inputTokens
        val outputTokens = first.outputTokens + retry.outputTokens

        parse(retry.toolInputJson)?.let { return toGenerated(it, inputTokens, outputTokens) }

        // Both attempts failed → fallback: show the raw reply, leave the task untouched,
        // and report the stage NOT complete (so the chain can't advance on garbage).
        return GeneratedResponse(
            reply = retry.toolInputJson,
            taskUpdate = null,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            stageComplete = false,
        )
    }

    private suspend fun completeStructured(systemPrompt: String, messages: List<Message>): StructuredResult =
        llmClient.completeStructured(
            systemPrompt = systemPrompt,
            messages = messages,
            toolName = TOOL_NAME,
            toolDescription = TOOL_DESCRIPTION,
            inputSchema = OUTPUT_SCHEMA,
        )

    /** Decode the tool input into [CombinedOutput], or null if it isn't a usable structured result. */
    private fun parse(toolInputJson: String): CombinedOutput? =
        runCatching { JSON.decodeFromString<CombinedOutput>(toolInputJson) }.getOrNull()

    private fun toGenerated(parsed: CombinedOutput, inputTokens: Int, outputTokens: Int) =
        GeneratedResponse(
            reply = parsed.reply,
            taskUpdate = parsed.taskUpdate,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            stageComplete = parsed.stageComplete,
        )

    @Serializable
    private data class CombinedOutput(
        val reply: String,
        @SerialName("task_update") val taskUpdate: String,
        // Optional (default false) so an omitted field never breaks parsing.
        @SerialName("stage_complete") val stageComplete: Boolean = false,
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        const val TOOL_NAME = "respond"

        // Appended to the system prompt for the single retry when the first structured
        // attempt didn't parse (transient — not part of the single assembly point).
        const val RETRY_REMINDER =
            "\n\n# Format reminder\nYour previous response was not in the required structured " +
                "format. Respond ONLY by calling the tool — do NOT put JSON or your answer in " +
                "plain text."

        // How to use the tool lives in the tool description (not appended to the
        // system prompt — Agent.buildSystemPrompt stays the single assembly point).
        // It references the current task's structure rather than re-listing the
        // sections, so WorkingMemory's task template remains the single source of
        // truth for the task format.
        val TOOL_DESCRIPTION = """
            Reply to the user and update the current task file. You MUST respond by CALLING
            this tool — do NOT write JSON or your answer as plain text in the message body.
            All three fields (reply, task_update, stage_complete) go INTO the tool call,
            never into free-form text. Provide three fields:
            - reply: your natural-language answer to the user. During PLANNING the reply IS
              the work — ask your clarifying/requirement-gathering questions in full, as
              many as needed (do not compress them). For EXECUTION and VALIDATION, make the
              reply a concise substantive summary of THIS stage's work — a few sentences
              naming the key components/findings and decisions — with the full detail in
              task_update's sections; reply is a recap pointing to the task, NOT a duplicate
              of the section and NOT a bare acknowledgement.
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
            - stage_complete: a boolean. Set true ONLY when the CURRENT stage's completion
              criterion (in the stage prompt) is genuinely and fully met — strict, no
              hand-waving, no "looks good". Each stage's section must hold that stage's OWN
              real work, never a restatement of earlier stages. Criteria: PLANNING —
              requirements are concrete and testable, key decisions are made, no blocking
              open questions remain; EXECUTION — ## Implementation contains a concrete
              component design (named components, interfaces, interactions, technical
              specifics), NOT a restatement of the requirements/decisions; VALIDATION —
              ## Validation contains actual review findings checked against each requirement
              (or their justified absence), NOT a restatement of the plan or design; DONE —
              terminal. Otherwise set false. Do NOT propose or name the next stage — the
              system decides transitions.
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
                putJsonObject("stage_complete") {
                    put("type", "boolean")
                    put("description", "True only if the CURRENT stage's completion criterion is fully met.")
                }
            }
            putJsonArray("required") {
                add("reply")
                add("task_update")
                add("stage_complete")
            }
        }
    }
}
