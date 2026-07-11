package org.example.service

import kotlinx.coroutines.CancellationException
import org.example.agent.LlmClient
import org.example.agent.Message
import org.example.agent.Role

/**
 * [Day 30] The chat service core — plain Kotlin, NO Ktor, so it's directly unit-testable with a fake
 * [LlmClient] (the routes in [ChatServer] are thin adapters over it). Turns one incoming chat turn into
 * a [ChatOutcome]:
 *
 *  1. rate-check the caller ([clientKey], the IP) → [ChatOutcome.RateLimited] if over the limit;
 *  2. resolve or mint the session id (a blank/absent id → a fresh UUID, returned to the client);
 *  3. cap the input length ("max context" limit) and append it to the session history;
 *  4. send the (trimmed) history to the model via [LlmClient.complete] with [systemPrompt];
 *  5. append the reply to the session and return [ChatOutcome.Reply].
 *
 * If the model call throws (e.g. Ollama is down — [org.example.llm.OllamaLlmClient] throws a clear
 * RuntimeException naming Ollama), it's caught and returned as [ChatOutcome.LlmError] so the server
 * responds with a clean error and stays up — never a 500 stack trace, never a crash.
 *
 * Depends only on [LlmClient] + [ChatSessions] + [RateLimiter] + plain config — never on the transport.
 */
class ChatService(
    private val llm: LlmClient,
    private val sessions: ChatSessions,
    private val rateLimiter: RateLimiter,
    private val maxInputChars: Int,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    suspend fun handle(sessionId: String?, clientKey: String, message: String): ChatOutcome {
        if (!rateLimiter.tryAcquire(clientKey)) return ChatOutcome.RateLimited

        val id = sessionId?.takeIf { it.isNotBlank() } ?: sessions.newId()
        val input = message.take(maxInputChars) // "max context": truncate an over-long single input
        val history = sessions.appendAndSnapshot(id, Message(Role.USER, input))

        val reply = try {
            llm.complete(systemPrompt, history).replyText
        } catch (e: CancellationException) {
            // Coroutine cancellation (client aborted / server shutdown) must propagate, not be swallowed.
            throw e
        } catch (e: RuntimeException) {
            // Ollama down / transport error — surface a clean message; the session keeps the user turn.
            return ChatOutcome.LlmError(id, e.message ?: "The local model is unavailable.")
        }

        sessions.append(id, Message(Role.ASSISTANT, reply))
        return ChatOutcome.Reply(id, reply)
    }

    companion object {
        /** A short plain-chat system prompt (NO RAG — the RAG prompt assumes retrieved context). */
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant running as a private local service. Answer the user directly " +
                "and concisely. If you are unsure, say so rather than inventing facts."
    }
}

/** The result of one chat turn; [ChatServer] maps each to an HTTP status. */
sealed interface ChatOutcome {
    /** Success → 200 `{sessionId, reply}`. */
    data class Reply(val sessionId: String, val reply: String) : ChatOutcome

    /** Caller over the rate limit → 429 `{error}`. */
    data object RateLimited : ChatOutcome

    /** Model/transport failure (e.g. Ollama down) → 503 `{sessionId, error}`. */
    data class LlmError(val sessionId: String, val error: String) : ChatOutcome
}
