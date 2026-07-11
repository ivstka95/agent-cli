package org.example.service

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.util.concurrent.CountDownLatch
import org.example.llm.LlmConfig
import org.example.llm.OllamaLlmClient

/**
 * [Day 30] Entry point for the local-LLM HTTP chat service (`./gradlew :app:runServer`).
 *
 * Wires the whole thing in one place and starts a Ktor CIO server (mirroring `:mcp`'s
 * `HttpServerTransportFactory`): builds the ONE long-lived [OllamaLlmClient] (it owns a CIO HttpClient —
 * reused across all requests, never per-request), the in-memory session store + rate limiter, and the
 * [ChatService], then serves [chatModule] on [ServiceConfig.host]:[ServiceConfig.port] (default
 * `0.0.0.0:8080`, reachable over the LAN/VPS). Blocks until Ctrl-C, then closes the client.
 *
 * The engine is Day-29's OPTIMIZED [LlmConfig]: low temperature (deterministic), a `num_predict` output
 * cap (concise + faster), and `num_ctx = 4096` (fits the accumulated history without the 2048 truncation).
 * Env vars still override every field via [LlmConfig.fromEnv].
 */
fun main() {
    val config = ServiceConfig.fromEnv()
    // Day-29's optimized profile (low temperature, capped output, 4096 ctx); env vars still override.
    val llmConfig = LlmConfig.fromEnv().optimized()
    val client = OllamaLlmClient(llmConfig)
    val service = ChatService(
        llm = client,
        sessions = ChatSessions(config.maxHistory),
        rateLimiter = RateLimiter(config.rateLimitPerMin),
        maxInputChars = config.maxInputChars,
    )
    val chatHtml = loadChatHtml()

    val engine = embeddedServer(CIO, host = config.host, port = config.port) {
        chatModule(service, chatHtml)
    }
    engine.start(wait = false)

    println("Local LLM chat service on ${config.localUrl} (bound ${config.host}:${config.port})")
    println("  model=${llmConfig.chatModel} host=${llmConfig.host} · rate=${config.rateLimitPerMin}/min · maxHistory=${config.maxHistory}")
    println("  open it in a browser, or reach it over the network at http://<this-machine-ip>:${config.port}")
    println("  press Ctrl-C to stop.")

    // Block until the process is asked to stop (mirrors :mcp's ServerMain latch), then clean up.
    val latch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { engine.stop(gracePeriodMillis = 0, timeoutMillis = 500) }
            runCatching { client.close() }
            latch.countDown()
        },
    )
    latch.await()
}
