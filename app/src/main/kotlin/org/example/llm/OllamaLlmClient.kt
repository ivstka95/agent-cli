package org.example.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.example.agent.LlmClient
import org.example.agent.LlmResult
import org.example.agent.Message
import org.example.agent.Role
import org.example.agent.StructuredResult
import java.io.IOException

/**
 * [Day 27] [LlmClient] backed by a LOCAL Ollama server's `/api/chat` — lets the whole agent/RAG/task
 * stack run OFFLINE on a local model (e.g. llama3.2), swapped in behind the same interface as the cloud
 * [AnthropicClient]. Reuses `:rag`'s `OllamaEmbedder` Ktor idiom (CIO + content negotiation, injectable
 * engine for tests, readable errors, `close()`).
 *
 * Implements [complete] and [completeStructured]; [runToolTurn] is intentionally left as the interface
 * default (throws) — the local model has no native tool-use, and `AgenticLoop` degrades to a plain reply
 * when it hits that. Ollama has NO top-level `system` field (unlike Anthropic), so the system prompt is
 * sent as a leading `system`-role message.
 */
class OllamaLlmClient(
    private val config: LlmConfig,
    // Injectable engine for tests (Ktor MockEngine); production uses CIO.
    engine: HttpClientEngine? = null,
) : LlmClient, AutoCloseable {

    private val http = if (engine != null) {
        HttpClient(engine) { install(ContentNegotiation) { json(json) } }
    } else {
        HttpClient(CIO) {
            // A local model can be slow on first token / cold load; give generous headroom.
            engine { requestTimeout = 120_000 }
            install(ContentNegotiation) { json(json) }
        }
    }

    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult {
        val parsed = postChat(
            ChatRequest(
                model = config.chatModel,
                messages = buildMessages(systemPrompt, messages),
                options = optionsFromConfig(),
            ),
        )
        return LlmResult(
            replyText = parsed.message.content,
            inputTokens = parsed.promptEvalCount,
            outputTokens = parsed.evalCount,
        )
    }

    override suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult {
        // Ollama's `format` constrains the output SHAPE (the JSON schema), but NOT the field-level
        // semantics Anthropic carried in the tool description (e.g. "quote verbatim", "stage_complete
        // only when..."). Fold that guidance into the system message so a local model still gets it.
        val system = systemPrompt +
            "\n\n# Structured output ($toolName)\n" +
            "Respond with ONLY a single JSON object matching the required schema. $toolDescription"
        val parsed = postChat(
            ChatRequest(
                model = config.chatModel,
                messages = buildMessages(system, messages),
                format = inputSchema,
                options = optionsFromConfig(),
            ),
        )
        // message.content is the JSON string conforming to `format` → hand it back verbatim; the caller
        // parses it (with its own retry + graceful fallback on malformed local output).
        return StructuredResult(
            toolInputJson = parsed.message.content,
            inputTokens = parsed.promptEvalCount,
            outputTokens = parsed.evalCount,
        )
    }

    /**
     * [Day 29] Ollama generation options from [config], or null when NONE are set — a null `options` is
     * omitted (`explicitNulls = false`), so an unset config produces a byte-identical body to Day 27/28.
     * Individual null fields are likewise omitted, so a config that sets only some options sends only those.
     */
    private fun optionsFromConfig(): OllamaOptions? {
        if (config.temperature == null && config.maxTokens == null && config.contextWindow == null) return null
        return OllamaOptions(
            temperature = config.temperature,
            numPredict = config.maxTokens,
            numCtx = config.contextWindow,
        )
    }

    /** Leading `system` message (Ollama has no top-level system field) + the mapped dialog. */
    private fun buildMessages(system: String, messages: List<Message>): List<ChatMessageDto> = buildList {
        add(ChatMessageDto(role = "system", content = system))
        messages.forEach { add(ChatMessageDto(role = it.role.toApiRole(), content = it.content)) }
    }

    private suspend fun postChat(request: ChatRequest): ChatResponse {
        val response: HttpResponse = try {
            http.post("${config.host}/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } catch (e: IOException) {
            // Connection refused etc. — name Ollama so the fix is obvious (the REPL prints e.message).
            throw RuntimeException(
                "Ollama request failed — is it running at ${config.host}? (start it with `ollama serve`): ${e.message}",
                e,
            )
        }

        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            throw RuntimeException("Ollama chat error ${response.status.value}: $body")
        }

        return response.body()
    }

    override fun close() = http.close()

    private fun Role.toApiRole(): String = when (this) {
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true // tolerate Ollama's extra timing/stats fields
            // `stream` defaults to false but MUST be emitted — Ollama defaults stream=true, and a streamed
            // body would break single-object parsing. `explicitNulls = false` omits `format` on plain
            // (non-structured) calls while still emitting the `stream` default.
            encodeDefaults = true
            explicitNulls = false
        }
    }

    // ── Ollama /api/chat DTOs ────────────────────────────────────────────────────
    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessageDto>,
        // A JSON Schema object → Ollama constrains the reply to match it (structured outputs). Null on a
        // plain chat call; `explicitNulls = false` then omits it from the request body.
        val format: JsonObject? = null,
        val stream: Boolean = false,
        // [Day 29] Generation tuning; null (default) → omitted, so Ollama's own defaults apply.
        val options: OllamaOptions? = null,
    )

    // [Day 29] Ollama's generation options object. All nullable → `explicitNulls = false` omits any unset
    // field, so we send only what the config specifies. `num_predict`/`num_ctx` are Ollama's snake_case names.
    @Serializable
    private data class OllamaOptions(
        val temperature: Double? = null,
        @SerialName("num_predict") val numPredict: Int? = null,
        @SerialName("num_ctx") val numCtx: Int? = null,
    )

    @Serializable
    private data class ChatMessageDto(val role: String, val content: String)

    @Serializable
    private data class ChatResponse(
        val message: ChatMessageContent = ChatMessageContent(),
        // Token usage on non-streamed responses; absent → 0 (never breaks parsing).
        @SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
        @SerialName("eval_count") val evalCount: Int = 0,
    )

    @Serializable
    private data class ChatMessageContent(val role: String = "assistant", val content: String = "")
}
