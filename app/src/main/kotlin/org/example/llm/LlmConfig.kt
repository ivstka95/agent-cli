package org.example.llm

/**
 * [Day 27] Config for the LOCAL generative client ([OllamaLlmClient]) — env-overridable, mirroring
 * `RagConfig.fromEnv()` / `:mcp`'s `ServerBindConfig.fromEnv()`.
 *
 * [chatModel] is the GENERATIVE (chat) model and is deliberately SEPARATE from `RagConfig.ollamaModel`,
 * which is the EMBEDDING model (`nomic-embed-text`). Both hit the same Ollama server, so [host] reuses
 * the same `OLLAMA_HOST` env var; the chat model has its own `OLLAMA_CHAT_MODEL`.
 */
data class LlmConfig(
    val host: String = DEFAULT_HOST,
    val chatModel: String = DEFAULT_CHAT_MODEL,
) {
    companion object {
        const val DEFAULT_HOST = "http://localhost:11434"
        const val DEFAULT_CHAT_MODEL = "llama3.2"

        fun fromEnv(): LlmConfig = LlmConfig(
            host = env("OLLAMA_HOST") ?: DEFAULT_HOST,
            chatModel = env("OLLAMA_CHAT_MODEL") ?: DEFAULT_CHAT_MODEL,
        )

        private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    }
}
