package org.example.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.example.agent.LlmClient
import org.example.agent.LlmResult
import org.example.agent.LlmTurn
import org.example.agent.Message
import org.example.agent.StructuredResult
import org.example.agent.ToolExchange
import org.example.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A cloud-like fake: implements runToolTurn (native tool-use). */
private class AnswerLlm : LlmClient {
    override suspend fun complete(systemPrompt: String, messages: List<Message>) = LlmResult("answer-llm", 0, 0)
    override suspend fun completeStructured(
        systemPrompt: String, messages: List<Message>, toolName: String, toolDescription: String, inputSchema: JsonObject,
    ) = StructuredResult("{}", 0, 0)
    override suspend fun runToolTurn(
        systemPrompt: String, messages: List<Message>, exchanges: List<ToolExchange>, tools: List<ToolSpec>,
    ): LlmTurn = LlmTurn.Answer("tool-answer", 0, 0)
}

/** A local-like fake: no native tool-use — runToolTurn left as the throwing interface default. */
private class ToollessLlm : LlmClient {
    override suspend fun complete(systemPrompt: String, messages: List<Message>) = LlmResult("toolless-llm", 0, 0)
    override suspend fun completeStructured(
        systemPrompt: String, messages: List<Message>, toolName: String, toolDescription: String, inputSchema: JsonObject,
    ) = StructuredResult("{}", 0, 0)
}

class LlmProviderTest {

    @Test
    fun `parse maps aliases (trimmed, case-insensitive) and rejects unknown`() {
        assertEquals(Provider.LOCAL, Provider.parse("local"))
        assertEquals(Provider.LOCAL, Provider.parse("Ollama"))
        assertEquals(Provider.CLOUD, Provider.parse("cloud"))
        assertEquals(Provider.CLOUD, Provider.parse(" ANTHROPIC "))
        assertNull(Provider.parse("gpt"))
    }

    @Test
    fun `factory builds the local client without a network call or api key`() {
        val client = LlmClientFactory.build(Provider.LOCAL)
        assertIs<OllamaLlmClient>(client)
        client.close()
    }

    @Test
    fun `switchable delegates runToolTurn to the active client`() = runBlocking {
        val cloud = SwitchableLlmClient(AnswerLlm())
        assertIs<LlmTurn.Answer>(cloud.runToolTurn("s", emptyList(), emptyList(), emptyList()))

        // A tool-less local backend surfaces the interface default — AgenticLoop catches this.
        val local = SwitchableLlmClient(ToollessLlm())
        val error = assertFailsWith<UnsupportedOperationException> {
            local.runToolTurn("s", emptyList(), emptyList(), emptyList())
        }
        // Trailing Unit-returning assertion so the runBlocking test body doesn't return the exception
        // (a non-Unit-returning @Test method is silently skipped by JUnit 5).
        assertTrue(error.message?.contains("runToolTurn") == true, error.message)
    }
}
