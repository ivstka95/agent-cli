package org.example.repl

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.example.agent.Agent
import org.example.agent.GeneratedResponse
import org.example.agent.LlmClient
import org.example.agent.LlmResult
import org.example.agent.Message
import org.example.agent.ResponseGenerator
import org.example.agent.StructuredResult
import org.example.llm.LlmProviderSwitch
import org.example.llm.Provider
import org.example.memory.MemoryStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Agent-path generator with a fixed reply, so the test focuses on the provider tag. */
private class TagGenerator : ResponseGenerator {
    override suspend fun generate(systemPrompt: String, messages: List<Message>, currentTask: String?): GeneratedResponse =
        GeneratedResponse("agent reply", taskUpdate = null, inputTokens = 1, outputTokens = 1)
}

/** Minimal LLM for the switch's builders (never actually called — the tag reads switch.current). */
private class DummyLlm : LlmClient {
    override suspend fun complete(systemPrompt: String, messages: List<Message>) = LlmResult("x", 0, 0)
    override suspend fun completeStructured(
        systemPrompt: String, messages: List<Message>, toolName: String, toolDescription: String, inputSchema: JsonObject,
    ) = StructuredResult("{}", 0, 0)
}

class ReplLlmTest {

    private val root: File = createTempDirectory("repl-llm").toFile()
    private val out = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun printed(substring: String) = out.any { it.contains(substring) }

    private fun switch(cloudFails: Boolean = false): LlmProviderSwitch =
        LlmProviderSwitch(Provider.LOCAL) { p ->
            when (p) {
                Provider.LOCAL -> DummyLlm()
                Provider.CLOUD ->
                    if (cloudFails) throw IllegalStateException("Missing ANTHROPIC_API_KEY") else DummyLlm()
            }
        }

    private fun replWith(sw: LlmProviderSwitch): Repl {
        val memory = MemoryStore(root)
        val agent = Agent(TagGenerator(), memory)
        return Repl(agent, memory, out::add, llmSwitch = sw)
    }

    @Test
    fun `answers are tagged with the active provider`() = runBlocking {
        val repl = replWith(switch())

        repl.submit("hello")

        assertTrue(printed("[local] Agent: agent reply"), out.toString())
    }

    @Test
    fun `llm with no arg shows the current provider`() = runBlocking {
        val repl = replWith(switch())

        repl.submit(":llm")

        assertTrue(printed("LLM provider: local"))
    }

    @Test
    fun `llm cloud switches the provider and retags answers`() = runBlocking {
        val repl = replWith(switch())

        repl.submit(":llm cloud")
        assertTrue(printed("LLM provider: cloud."))

        repl.submit("hello")
        assertTrue(printed("[cloud] Agent: agent reply"), out.toString())
    }

    @Test
    fun `llm with a bad arg prints usage`() = runBlocking {
        val repl = replWith(switch())

        repl.submit(":llm bogus")

        assertTrue(printed("Usage: :llm [local|cloud]"))
    }

    @Test
    fun `switching to an unavailable cloud keeps local and never crashes`() = runBlocking {
        val repl = replWith(switch(cloudFails = true))

        repl.submit(":llm cloud")
        assertTrue(printed("Can't switch to cloud"))
        assertTrue(printed("Staying on local"))

        repl.submit("hello")
        assertTrue(printed("[local] Agent: agent reply"), out.toString())
    }
}
