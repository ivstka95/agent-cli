package org.example.ragmode

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.example.agent.LlmClient
import org.example.agent.LlmResult
import org.example.agent.Message
import org.example.agent.StructuredResult
import org.example.rag.config.RagConfig
import org.example.rag.index.SearchResult
import org.example.rag.model.Chunk
import org.example.rag.model.ChunkMetadata
import org.example.rag.retrieve.IndexStrategy
import org.example.rag.retrieve.RagRetriever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records the system prompt + messages it was asked to complete; returns a canned reply. */
private class RecordingLlmClient(private val reply: String = "the answer") : LlmClient {
    var systemPrompt: String? = null
    var messages: List<Message> = emptyList()

    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult {
        this.systemPrompt = systemPrompt
        this.messages = messages
        return LlmResult(reply, inputTokens = 7, outputTokens = 3)
    }

    override suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult = throw UnsupportedOperationException()
}

/** Fake retriever: returns canned hits and records whether it was invoked. */
private class FakeRagRetriever(private val hits: List<SearchResult>) : RagRetriever {
    var called = false
    var lastTopK = -1

    override suspend fun retrieve(question: String, topK: Int): List<SearchResult> {
        called = true
        lastTopK = topK
        return hits
    }
}

class RagResponderTest {

    private fun hit(file: String, section: String?, text: String, score: Float): SearchResult =
        SearchResult(
            Chunk(
                text = text,
                metadata = ChunkMetadata(
                    source = "app/src/$file",
                    file = file,
                    section = section,
                    chunkId = "app/src/$file#structural#0",
                    strategy = "structural",
                    ordinal = 0,
                ),
            ),
            score,
        )

    private fun responder(llm: LlmClient, retriever: FakeRagRetriever) =
        RagResponder(llm, RagConfig(topK = 5), retrieverFactory = { retriever })

    @Test
    fun `with RAG - assembles context with sources and anti-hallucination instruction`() = runBlocking {
        val llm = RecordingLlmClient()
        val retriever = FakeRagRetriever(
            listOf(
                hit("Agent.kt", "buildSystemPrompt", "assembles the system prompt", 0.9f),
                hit("Repl.kt", "handleCommand", "dispatches commands", 0.7f),
            ),
        )

        val answer = responder(llm, retriever).answer("Where is the system prompt built?", useRag = true)

        assertTrue(retriever.called)
        assertEquals(5, retriever.lastTopK) // topK from config
        // Anti-hallucination instruction is in the system prompt.
        assertTrue(llm.systemPrompt!!.contains("does not contain the answer"))
        // Context block carries the [Source: file, section: …] prefixes and the chunk text.
        val userContent = llm.messages.single().content
        assertTrue(userContent.contains("[Source: Agent.kt, section: buildSystemPrompt]"))
        assertTrue(userContent.contains("[Source: Repl.kt, section: handleCommand]"))
        assertTrue(userContent.contains("assembles the system prompt"))
        assertTrue(userContent.contains("Question: Where is the system prompt built?"))
        // Deterministic Sources: line appended to the answer, from metadata.
        assertEquals(listOf("Agent.kt:buildSystemPrompt", "Repl.kt:handleCommand"), answer.sources)
        assertTrue(answer.answer.endsWith("Sources: [Agent.kt:buildSystemPrompt, Repl.kt:handleCommand]"))
        assertTrue(answer.answer.startsWith("the answer"))
    }

    @Test
    fun `without RAG - bare question reaches the LLM and retriever is never touched`() = runBlocking {
        val llm = RecordingLlmClient("bare reply")
        val retriever = FakeRagRetriever(emptyList())

        val answer = responder(llm, retriever).answer("What is 2+2?", useRag = false)

        assertFalse(retriever.called) // baseline skips retrieval entirely
        assertEquals(RagResponder.BASELINE_SYSTEM, llm.systemPrompt)
        assertEquals("What is 2+2?", llm.messages.single().content) // bare question, no context
        assertEquals("bare reply", answer.answer)
        assertTrue(answer.sources.isEmpty())
    }

    @Test
    fun `sources are de-duplicated by file and section`() {
        val hits = listOf(
            hit("Agent.kt", "buildSystemPrompt", "a", 0.9f),
            hit("Agent.kt", "buildSystemPrompt", "b", 0.8f), // same file+section
            hit("Repl.kt", null, "c", 0.7f),
        )
        assertEquals(
            listOf("Agent.kt:buildSystemPrompt", "Repl.kt:—"),
            RagResponder.sourcesOf(hits),
        )
    }

    @Test
    fun `setStrategy switches which retriever is built`() {
        val structural = FakeRagRetriever(emptyList())
        val fixed = FakeRagRetriever(emptyList())
        val responder = RagResponder(RecordingLlmClient(), RagConfig(), retrieverFactory = { s ->
            if (s == IndexStrategy.STRUCTURAL) structural else fixed
        })
        assertEquals(IndexStrategy.STRUCTURAL, responder.strategy)

        runBlocking { responder.answer("q", useRag = true) }
        assertTrue(structural.called)
        assertFalse(fixed.called)

        responder.strategy = IndexStrategy.FIXED
        runBlocking { responder.answer("q", useRag = true) }
        assertTrue(fixed.called)
    }
}
