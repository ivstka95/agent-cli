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
import org.example.memory.MemoryStore
import org.example.rag.config.RagConfig
import org.example.rag.index.SearchResult
import org.example.rag.model.Chunk
import org.example.rag.model.ChunkMetadata
import org.example.rag.retrieve.RagRetriever
import org.example.rag.retrieve.RetrievalResult
import org.example.ragmode.RagResponder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Agent-path generator that flags if it was invoked (the standalone `:rag` path must bypass it). */
private class GroundTrackingGenerator : ResponseGenerator {
    var called = false

    override suspend fun generate(systemPrompt: String, messages: List<Message>, currentTask: String?): GeneratedResponse {
        called = true
        return GeneratedResponse("agent reply", taskUpdate = null, inputTokens = 1, outputTokens = 1)
    }
}

/** One-hit retriever wired into the AGENT (Day 25), so a grounded turn shows sources. */
private class GroundStubRetriever : RagRetriever {
    override suspend fun retrieve(question: String, topK: Int): RetrievalResult {
        val hit = SearchResult(
            Chunk(
                text = "the loop chains tool calls",
                metadata = ChunkMetadata(
                    source = "app/AgenticLoop.kt", file = "AgenticLoop.kt", section = "run",
                    chunkId = "app/AgenticLoop.kt#structural#0", strategy = "structural", ordinal = 0,
                ),
            ),
            0.9f,
        )
        return RetrievalResult(listOf(hit), retrievedCount = 1)
    }
}

/** Minimal LLM for the standalone RagResponder used in the `:rag`-precedence test. */
private class GroundStubLlmClient : LlmClient {
    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult =
        LlmResult("standalone reply", inputTokens = 1, outputTokens = 1)

    override suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult =
        StructuredResult(
            """{"answer":"standalone reply","citations":[],"dont_know":false}""",
            inputTokens = 1,
            outputTokens = 1,
        )
}

class ReplGroundTest {

    private val root: File = createTempDirectory("repl-ground").toFile()
    private val out = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun printed(substring: String) = out.any { it.contains(substring) }

    private fun replWith(gen: GroundTrackingGenerator, responder: RagResponder? = null): Repl {
        val memory = MemoryStore(root)
        val agent = Agent(gen, memory, ragRetriever = GroundStubRetriever(), ragSearchK = 5)
        return Repl(agent, memory, out::add, responder)
    }

    private fun standaloneResponder() =
        RagResponder(GroundStubLlmClient(), RagConfig(), retrieverFactory = { _, _ -> GroundStubRetriever() })

    @Test
    fun `ground is on by default so a question is grounded with sources via the agent path`() = runBlocking {
        val gen = GroundTrackingGenerator()
        val repl = replWith(gen)

        repl.submit("How does the agentic loop chain tool calls?")

        assertTrue(gen.called, "the agent path is used")
        assertTrue(printed("Agent: agent reply"))
        assertTrue(printed("sources: [AgenticLoop.kt:run]"), "grounded turn always shows sources")
    }

    @Test
    fun `ground off gives a plain agent turn with no sources`() = runBlocking {
        val gen = GroundTrackingGenerator()
        val repl = replWith(gen)

        repl.submit(":ground off")
        assertTrue(printed("Grounding: off."))

        repl.submit("plain question")
        assertTrue(gen.called, "still the agent path")
        assertFalse(printed("sources:"), "no retrieval when grounding is off")
    }

    @Test
    fun `ground with no arg reports the default-on state, and toggles report state`() = runBlocking {
        val repl = replWith(GroundTrackingGenerator())

        repl.submit(":ground")
        assertTrue(printed("Grounding: on"))

        repl.submit(":ground on")
        assertTrue(printed("Grounding: on."))

        repl.submit(":ground bogus")
        assertTrue(printed("Usage: :ground [on|off]"))
    }

    @Test
    fun `rag on still routes to the standalone path, bypassing the grounded agent`() = runBlocking {
        val gen = GroundTrackingGenerator()
        val repl = replWith(gen, standaloneResponder())

        repl.submit(":rag on")
        repl.submit("How does the agentic loop chain tool calls?")

        assertFalse(gen.called, "the standalone :rag path must bypass the agent")
        assertTrue(printed("Agent: standalone reply"))
    }
}
