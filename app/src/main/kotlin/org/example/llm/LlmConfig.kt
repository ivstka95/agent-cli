package org.example.llm

/**
 * [Day 27] Config for the LOCAL generative client ([OllamaLlmClient]) — env-overridable, mirroring
 * `RagConfig.fromEnv()` / `:mcp`'s `ServerBindConfig.fromEnv()`.
 *
 * [chatModel] is the GENERATIVE (chat) model and is deliberately SEPARATE from `RagConfig.ollamaModel`,
 * which is the EMBEDDING model (`nomic-embed-text`). Both hit the same Ollama server, so [host] reuses
 * the same `OLLAMA_HOST` env var; the chat model has its own `OLLAMA_CHAT_MODEL`.
 *
 * [Day 29] Generation tuning — [temperature], [maxTokens] (Ollama `num_predict`), and [contextWindow]
 * (Ollama `num_ctx`). All NULLABLE and default null: when unset, [OllamaLlmClient] omits them entirely so
 * the `/api/chat` body is byte-identical to Day 27/28 and Ollama's own defaults apply. They exist so the
 * local model can be tuned for our task (factual, concise, code-grounded); the Day-29 optimization runner
 * sets them explicitly, and the REPL honors the env vars when present.
 */
data class LlmConfig(
    val host: String = DEFAULT_HOST,
    val chatModel: String = DEFAULT_CHAT_MODEL,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val contextWindow: Int? = null,
) {
    companion object {
        const val DEFAULT_HOST = "http://localhost:11434"
        const val DEFAULT_CHAT_MODEL = "llama3.2"

        /**
         * [getenv] is injectable so the numeric env parsing is unit-testable offline; production uses the
         * real environment. Numeric vars mirror `RagConfig`'s `toDoubleOrNull` / `toIntOrNull` pattern —
         * a blank or unparseable value falls back to null (Ollama default), never crashes.
         */
        fun fromEnv(getenv: (String) -> String? = System::getenv): LlmConfig {
            fun env(name: String): String? = getenv(name)?.takeIf { it.isNotBlank() }
            return LlmConfig(
                host = env("OLLAMA_HOST") ?: DEFAULT_HOST,
                chatModel = env("OLLAMA_CHAT_MODEL") ?: DEFAULT_CHAT_MODEL,
                temperature = env("OLLAMA_TEMPERATURE")?.toDoubleOrNull(),
                maxTokens = env("OLLAMA_NUM_PREDICT")?.toIntOrNull(),
                contextWindow = env("OLLAMA_NUM_CTX")?.toIntOrNull(),
            )
        }
    }
}
