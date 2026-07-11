package org.example.service

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * [Day 30] The thin Ktor layer over [ChatService] — this is the ONLY Ktor-aware file besides the entry
 * point, and it holds no logic: it installs JSON + a catch-all error handler, serves the chat page at
 * `GET /`, and adapts `POST /chat` to [ChatService.handle], mapping each [ChatOutcome] to a status.
 *
 * Mirrors `:mcp`'s `HttpServerTransportFactory` (Ktor CIO). The entry point ([ChatServerMain]) starts
 * the actual `embeddedServer(CIO, …)`; the module is exposed as [chatModule] so the offline
 * `testApplication` route test can mount it without binding a socket.
 */
fun Application.chatModule(service: ChatService, chatHtml: String) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }

    // StatusPages turns thrown errors into clean JSON so the server never emits a stack trace and stays up.
    // A malformed/unparseable body is the caller's fault → 400 with a short reason; anything else is a
    // server-side bug → 500 with a generic message (don't mislabel it 400 or leak internals to the client).
    // Expected failures (Ollama down, rate limit) are modeled as ChatOutcome and never reach here.
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(error = "Bad request: ${cause.message ?: "invalid body"}"),
            )
        }
        exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error = "Internal server error."))
        }
    }

    routing {
        get("/") { call.respondText(chatHtml, ContentType.Text.Html) }

        post("/chat") {
            val request = call.receive<ChatRequest>()
            // Rate-limit key = client IP; falls back to the session id when the IP is unavailable.
            val clientKey = call.request.origin.remoteHost.ifBlank { request.sessionId ?: "unknown" }
            when (val outcome = service.handle(request.sessionId, clientKey, request.message)) {
                is ChatOutcome.Reply ->
                    call.respond(HttpStatusCode.OK, ChatResponse(outcome.sessionId, outcome.reply))
                ChatOutcome.RateLimited ->
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorResponse(error = "Rate limit exceeded — please slow down and retry shortly."),
                    )
                is ChatOutcome.LlmError ->
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse(outcome.sessionId, outcome.error),
                    )
            }
        }
    }
}

/** Loads the static chat page bundled in `resources/chat.html`. Fails fast if it's missing. */
fun loadChatHtml(): String =
    ChatService::class.java.getResource("/chat.html")?.readText()
        ?: error("chat.html not found on the classpath (app/src/main/resources/chat.html)")
