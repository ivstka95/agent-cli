package org.example.service

import kotlinx.serialization.Serializable

/**
 * [Day 30] Wire DTOs for POST /chat.
 *
 * The client sends [ChatRequest] — [sessionId] is null on the first turn (the server mints one) and
 * echoed back on every following turn so its history continues. Success returns [ChatResponse]; a
 * rate-limit or Ollama-down error returns [ErrorResponse] (the HTML reads `reply ?? error`).
 */
@Serializable
data class ChatRequest(val sessionId: String? = null, val message: String)

@Serializable
data class ChatResponse(val sessionId: String, val reply: String)

@Serializable
data class ErrorResponse(val sessionId: String? = null, val error: String)
