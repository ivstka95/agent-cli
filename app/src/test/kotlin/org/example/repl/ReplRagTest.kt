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
import org.example.rag.retrieve.IndexStrategy
import org.example.rag.retrieve.RagRetriever
import org.example.ragmode.RagResponder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Agent-path generator that flags if it was ever invoked (RAG mode must bypass it). */
private class TrackingGenerator : ResponseGenerator {
    var called = false

    override suspend fun generate(systemPrompt: String, messages: List<Message>, currentTask: String?): GeneratedResponse {
        called = true
        return GeneratedResponse("agent reply", taskUpdate = null, inputTokens = 1, outputTokens = 1)
    }
}

private class StubLlmClient(private val reply: String) : LlmClient {
    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult =
        LlmResult(reply, inputTokens = 2, outputTokens = 2)

    override suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult = throw UnsupportedOperationException()
}

private class StubRetriever : RagRetriever {
    override suspend fun retrieve(question: String, topK: Int): List<SearchResult> =
        listOf(
            SearchResult(
                Chunk(
                    text = "the loop chains tool calls",
                    metadata = ChunkMetadata(
                        source = "app/AgenticLoop.kt", file = "AgenticLoop.kt", section = "run",
                        chunkId = "app/AgenticLoop.kt#structural#0", strategy = "structural", ordinal = 0,
                    ),
                ),
                0.9f,
            ),
        )
}

class ReplRagTest {

    private val root: File = createTempDirectory("repl-rag").toFile()
    private val out = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun printed(substring: String) = out.any { it.contains(substring) }

    private fun replWith(gen: TrackingGenerator, responder: RagResponder?): Repl {
        val memory = MemoryStore(root)
        return Repl(Agent(gen, memory), memory, out::add, responder)
    }

    private fun responder(reply: String = "grounded answer") =
        RagResponder(StubLlmClient(reply), RagConfig(), retrieverFactory = { StubRetriever() })

    @Test
    fun `rag toggles on and off and reports state`() = runBlocking {
        val repl = replWith(TrackingGenerator(), responder())

        repl.submit(":rag")
        assertTrue(printed("RAG mode: off"))

        repl.submit(":rag on")
        assertTrue(printed("RAG mode: on (index: structural)"))

        repl.submit(":rag off")
        assertTrue(printed("RAG mode: off."))

        repl.submit(":rag bogus")
        assertTrue(printed("Usage: :rag [on|off]"))
    }

    @Test
    fun `index switches strategy and rejects unknown`() = runBlocking {
        val repl = replWith(TrackingGenerator(), responder())

        repl.submit(":index")
        assertTrue(printed("Index: structural"))

        repl.submit(":index fixed")
        assertTrue(printed("Index: fixed-size."))

        repl.submit(":index bogus")
        assertTrue(printed("Invalid index: 'bogus'"))
    }

    @Test
    fun `when rag on a question routes to the retriever+LLM path not the agent`() = runBlocking {
        val gen = TrackingGenerator()
        val repl = replWith(gen, responder("grounded answer"))

        repl.submit(":rag on")
        repl.submit("How does the agentic loop chain tool calls?")

        assertFalse(gen.called, "RAG mode must bypass the agent path")
        assertTrue(printed("Agent: grounded answer"))
        assertTrue(printed("Sources: [AgenticLoop.kt:run]"), "deterministic sources appended")
    }

    @Test
    fun `when rag off a question routes to the agent path`() = runBlocking {
        val gen = TrackingGenerator()
        val repl = replWith(gen, responder())

        repl.submit("regular question")

        assertTrue(gen.called, "RAG off must use the agent path")
    }

    @Test
    fun `rag command reports unavailable when no responder is wired`() = runBlocking {
        val repl = replWith(TrackingGenerator(), responder = null)

        repl.submit(":rag on")
        assertTrue(printed("RAG is not available"))
    }
}
