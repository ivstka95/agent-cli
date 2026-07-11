package org.example.service

/**
 * [Day 30] Config for the local-LLM HTTP chat service — env-overridable, mirroring
 * `LlmConfig.fromEnv()` / `:mcp`'s `ServerBindConfig.fromEnv()`.
 *
 * The service exposes the EXISTING [org.example.llm.OllamaLlmClient] over HTTP. [host] defaults to
 * `0.0.0.0` (not localhost) so another device on the LAN — or a VPS client — can reach it; a VPS deploy
 * later is a config change (env vars), not a code change.
 *
 * The two limits the Day-30 task requires:
 *  - [rateLimitPerMin] — max POST /chat requests per client (IP) per 60s window; over it → HTTP 429.
 *  - [maxHistory] / [maxInputChars] — the "max context" cap: keep only the last [maxHistory] messages
 *    before sending to the model, and truncate any single input to [maxInputChars]. A simple
 *    message-count + char budget (no token counting) is enough to keep the prompt within `num_ctx`.
 *
 * [getenv] is injectable so the parsing is unit-testable offline; production uses the real environment.
 * Numeric vars mirror `LlmConfig`'s `toIntOrNull` pattern — a blank/unparseable value falls back to the
 * default, never crashes.
 */
data class ServiceConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val rateLimitPerMin: Int = DEFAULT_RATE_LIMIT,
    val maxHistory: Int = DEFAULT_MAX_HISTORY,
    val maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS,
) {
    /** Base URL to print on startup (the local view; over the network use the machine's LAN/VPS IP). */
    val localUrl: String get() = "http://localhost:$port"

    companion object {
        const val DEFAULT_HOST = "0.0.0.0"
        const val DEFAULT_PORT = 8080
        const val DEFAULT_RATE_LIMIT = 20
        const val DEFAULT_MAX_HISTORY = 20
        const val DEFAULT_MAX_INPUT_CHARS = 4000

        fun fromEnv(getenv: (String) -> String? = System::getenv): ServiceConfig {
            fun env(name: String): String? = getenv(name)?.takeIf { it.isNotBlank() }
            return ServiceConfig(
                host = env("SERVICE_HOST") ?: DEFAULT_HOST,
                port = env("SERVICE_PORT")?.toIntOrNull() ?: DEFAULT_PORT,
                rateLimitPerMin = env("SERVICE_RATE_LIMIT")?.toIntOrNull() ?: DEFAULT_RATE_LIMIT,
                maxHistory = env("SERVICE_MAX_HISTORY")?.toIntOrNull() ?: DEFAULT_MAX_HISTORY,
                maxInputChars = env("SERVICE_MAX_INPUT_CHARS")?.toIntOrNull() ?: DEFAULT_MAX_INPUT_CHARS,
            )
        }
    }
}
