package org.example.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [Day 29] Verifies [LlmConfig.fromEnv] reads and parses the generation-tuning env vars offline, using an
 * injected fake environment (no real `System.getenv`). Covers: values present and parsed; unset → null
 * (Ollama defaults); blank and unparseable → null (never crashes).
 */
class LlmConfigTest {

    private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

    @Test
    fun `fromEnv reads host, chat model, and the generation options`() {
        val config = LlmConfig.fromEnv(
            envOf(
                "OLLAMA_HOST" to "http://ollama:1234",
                "OLLAMA_CHAT_MODEL" to "qwen2.5",
                "OLLAMA_TEMPERATURE" to "0.2",
                "OLLAMA_NUM_PREDICT" to "512",
                "OLLAMA_NUM_CTX" to "4096",
            ),
        )

        assertEquals("http://ollama:1234", config.host)
        assertEquals("qwen2.5", config.chatModel)
        assertEquals(0.2, config.temperature)
        assertEquals(512, config.maxTokens)
        assertEquals(4096, config.contextWindow)
    }

    @Test
    fun `unset options fall back to null so Ollama defaults apply`() {
        val config = LlmConfig.fromEnv(envOf()) // nothing set

        assertEquals(LlmConfig.DEFAULT_HOST, config.host)
        assertEquals(LlmConfig.DEFAULT_CHAT_MODEL, config.chatModel)
        assertNull(config.temperature)
        assertNull(config.maxTokens)
        assertNull(config.contextWindow)
    }

    @Test
    fun `blank or unparseable option values fall back to null without crashing`() {
        val config = LlmConfig.fromEnv(
            envOf(
                "OLLAMA_TEMPERATURE" to "   ", // blank
                "OLLAMA_NUM_PREDICT" to "lots", // not an Int
                "OLLAMA_NUM_CTX" to "", // empty
            ),
        )

        assertNull(config.temperature)
        assertNull(config.maxTokens)
        assertNull(config.contextWindow)
    }

    @Test
    fun `optimized fills the unset generation fields with the tuned profile`() {
        val config = LlmConfig().optimized() // all three fields unset

        assertEquals(LlmConfig.OPTIMIZED_TEMPERATURE, config.temperature)
        assertEquals(LlmConfig.OPTIMIZED_NUM_PREDICT, config.maxTokens)
        assertEquals(LlmConfig.OPTIMIZED_NUM_CTX, config.contextWindow)
    }

    @Test
    fun `optimized leaves already-set fields intact so env overrides win`() {
        val config = LlmConfig(temperature = 0.7, maxTokens = 256, contextWindow = 8192).optimized()

        assertEquals(0.7, config.temperature)
        assertEquals(256, config.maxTokens)
        assertEquals(8192, config.contextWindow)
    }
}
