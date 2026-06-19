package org.example.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
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
import org.example.agent.LlmClient
import org.example.agent.LlmResult
import org.example.agent.Message
import org.example.agent.Role

/**
 * [LlmClient] backed by the Anthropic Messages API, implemented with the Ktor client.
 *
 * The API key is read from the ANTHROPIC_API_KEY environment variable at construction
 * and never hardcoded, written to a file, or logged.
 */
class AnthropicClient(
    private val apiKey: String = readApiKeyFromEnv(),
) : LlmClient {

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult {
        val request = MessagesRequest(
            model = MODEL,
            maxTokens = MAX_TOKENS,
            system = systemPrompt,
            messages = messages.map { MessageDto(role = it.role.toApiRole(), content = it.content) },
        )

        val response: HttpResponse = http.post(MESSAGES_URL) {
            headers {
                append("x-api-key", apiKey)
                append("anthropic-version", ANTHROPIC_VERSION)
            }
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            // Surface a readable error instead of crashing on a deserialization failure.
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            throw RuntimeException("Anthropic API error ${response.status.value}: $body")
        }

        val parsed = response.body<MessagesResponse>()
        val text = parsed.content.firstOrNull { it.type == "text" }?.text
            ?: parsed.content.firstOrNull()?.text
            ?: ""

        return LlmResult(
            replyText = text,
            inputTokens = parsed.usage.inputTokens,
            outputTokens = parsed.usage.outputTokens,
        )
    }

    private fun Role.toApiRole(): String = when (this) {
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
    }

    companion object {
        private const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val MAX_TOKENS = 1024
        private const val API_KEY_ENV = "ANTHROPIC_API_KEY"

        private fun readApiKeyFromEnv(): String =
            System.getenv(API_KEY_ENV)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "Missing $API_KEY_ENV environment variable. " +
                        "Set it before running: export $API_KEY_ENV=sk-ant-...",
                )
    }
}

// ── Anthropic Messages API DTOs ────────────────────────────────────────────────

@Serializable
private data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<MessageDto>,
)

@Serializable
private data class MessageDto(
    val role: String,
    val content: String,
)

@Serializable
private data class MessagesResponse(
    val content: List<ContentBlock> = emptyList(),
    val usage: Usage = Usage(),
)

@Serializable
private data class ContentBlock(
    val type: String = "text",
    val text: String = "",
)

@Serializable
private data class Usage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
)
