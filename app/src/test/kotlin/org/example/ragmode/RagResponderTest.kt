package org.example.ragmode

import kotlinx.coroutines.runBlocking
import org.example.agent.LlmClient
import org.example.rag.config.RagConfig
import org.example.rag.index.SearchResult
import org.example.rag.model.Chunk
import org.example.rag.model.ChunkMetadata
import org.example.rag.retrieve.IndexStrategy
import org.example.rag.retrieve.RagRetriever
import org.example.rag.retrieve.RetrievalResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fake retriever: returns canned hits and records how it was invoked. [retrievedCount] simulates the
 * pre-filter candidate count (defaults to [hits] size when no filtering is modelled).
 */
private class FakeRagRetriever(
    private val hits: List<SearchResult>,
    private val retrievedCount: Int = hits.size,
) : RagRetriever {
    var called = false
    var lastTopK = -1

    override suspend fun retrieve(question: String, topK: Int): RetrievalResult {
        called = true
        lastTopK = topK
        return RetrievalResult(hits, retrievedCount = retrievedCount)
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
        RagResponder(llm, RagConfig(topK = 5), retrieverFactory = { _, _ -> retriever })

    @Test
    fun `with RAG - assembles context with sources and anti-hallucination instruction`() = runBlocking {
        val llm = RecordingLlmClient(
            structuredJson = """
                {"answer":"It's built in Agent.buildSystemPrompt.",
                 "citations":[{"quote":"assembles the system prompt","source":"Agent.kt:buildSystemPrompt"}],
                 "dont_know":false}
            """.trimIndent(),
        )
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
        assertTrue(llm.systemPrompt!!.contains("does not actually answer the question"))
        // Context block carries the [Source: file, section: …] prefixes and the chunk text.
        val userContent = llm.messages.single().content
        assertTrue(userContent.contains("[Source: Agent.kt, section: buildSystemPrompt]"))
        assertTrue(userContent.contains("[Source: Repl.kt, section: handleCommand]"))
        assertTrue(userContent.contains("assembles the system prompt"))
        assertTrue(userContent.contains("Question: Where is the system prompt built?"))
        // [Day 24] Answer is the structured field (no appended Sources line); sources stay deterministic.
        assertEquals("It's built in Agent.buildSystemPrompt.", answer.answer)
        assertEquals(listOf("Agent.kt:buildSystemPrompt", "Repl.kt:handleCommand"), answer.sources)
        assertFalse(answer.dontKnow)
        // [Day 24] Citation carries the verbatim quote + its source, verified against the chunk text.
        val citation = answer.citations.single()
        assertEquals("assembles the system prompt", citation.quote)
        assertEquals("Agent.kt:buildSystemPrompt", citation.source)
        assertTrue(citation.verbatim)
    }

    @Test
    fun `a normal answer always carries non-empty sources and citations`() = runBlocking {
        val llm = RecordingLlmClient(
            structuredJson = """
                {"answer":"grounded","citations":[{"quote":"dispatches commands","source":"Repl.kt:handleCommand"}],
                 "dont_know":false}
            """.trimIndent(),
        )
        val retriever = FakeRagRetriever(listOf(hit("Repl.kt", "handleCommand", "dispatches commands", 0.8f)))

        val answer = responder(llm, retriever).answer("How are commands handled?", useRag = true)

        assertTrue(answer.sources.isNotEmpty())
        assertTrue(answer.citations.isNotEmpty())
        assertTrue(answer.citations.all { it.verbatim })
    }

    @Test
    fun `a fabricated quote is flagged not verbatim`() = runBlocking {
        val llm = RecordingLlmClient(
            structuredJson = """
                {"answer":"grounded","citations":[{"quote":"this text is nowhere in the chunk","source":"Repl.kt:handleCommand"}],
                 "dont_know":false}
            """.trimIndent(),
        )
        val retriever = FakeRagRetriever(listOf(hit("Repl.kt", "handleCommand", "dispatches commands", 0.8f)))

        val answer = responder(llm, retriever).answer("q", useRag = true)

        assertFalse(answer.citations.single().verbatim) // the quote isn't a substring of any chunk
    }

    @Test
    fun `dont-know path returns the clarification ask with no citations`() = runBlocking {
        val llm = RecordingLlmClient(
            structuredJson = """{"answer":"I don't know — please clarify.","citations":[],"dont_know":true}""",
        )
        // A chunk was retrieved (passed the threshold) but doesn't address the question.
        val retriever = FakeRagRetriever(listOf(hit("Repl.kt", "handleCommand", "dispatches commands", 0.6f)))

        val answer = responder(llm, retriever).answer("what's the weather?", useRag = true)

        assertTrue(answer.dontKnow)
        assertTrue(answer.answer.contains("clarify"))
        assertTrue(answer.citations.isEmpty())
        // Sources considered are still surfaced even when the model doesn't know.
        assertEquals(listOf("Repl.kt:handleCommand"), answer.sources)
    }

    @Test
    fun `structured parse failure falls back to raw text without crashing`() = runBlocking {
        val llm = RecordingLlmClient(structuredJson = "not valid json at all")
        val retriever = FakeRagRetriever(listOf(hit("Repl.kt", "handleCommand", "dispatches commands", 0.8f)))

        val answer = responder(llm, retriever).answer("q", useRag = true)

        assertEquals("not valid json at all", answer.answer) // raw payload surfaced, no exception
        assertTrue(answer.citations.isEmpty())
        assertFalse(answer.dontKnow)
        assertTrue(answer.sources.isNotEmpty()) // deterministic sources still present
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
        val responder = RagResponder(RecordingLlmClient(), RagConfig(), retrieverFactory = { s, _ ->
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

    @Test
    fun `improved pipeline searches retrieveK and reports before-after counts and scores`() = runBlocking {
        val llm = RecordingLlmClient()
        // Search returned 20 candidates; only 1 survived the filter.
        val retriever = FakeRagRetriever(
            hits = listOf(hit("JsonVectorIndex.kt", "save", "writes json to disk", 0.83f)),
            retrievedCount = 20,
        )
        val responder = RagResponder(
            llm, RagConfig(topK = 5, retrieveK = 20), retrieverFactory = { _, _ -> retriever },
        )

        val answer = responder.answer("how are indexes stored?", useRag = true, improved = true)

        assertEquals(20, retriever.lastTopK) // improved casts the wide net (retrieveK), not topK
        assertEquals(20, answer.retrievedBefore)
        assertEquals(1, answer.keptAfter)
        assertEquals(listOf("JsonVectorIndex.kt:save (0.83)"), answer.scoredSources)
    }

    @Test
    fun `improved flag selects a separately built retriever`() = runBlocking {
        val baseline = FakeRagRetriever(emptyList())
        val improved = FakeRagRetriever(emptyList())
        val responder = RagResponder(RecordingLlmClient(), RagConfig(), retrieverFactory = { _, isImproved ->
            if (isImproved) improved else baseline
        })

        responder.answer("q", useRag = true, improved = false)
        assertTrue(baseline.called)
        assertFalse(improved.called)

        responder.answer("q", useRag = true, improved = true)
        assertTrue(improved.called)
    }

    @Test
    fun `improved var toggle drives the default pipeline`() = runBlocking {
        val baseline = FakeRagRetriever(emptyList())
        val improved = FakeRagRetriever(emptyList())
        val responder = RagResponder(RecordingLlmClient(), RagConfig(), retrieverFactory = { _, isImproved ->
            if (isImproved) improved else baseline
        })
        responder.improved = true

        responder.answer("q", useRag = true) // no explicit flag ⇒ uses responder.improved
        assertTrue(improved.called)
        assertFalse(baseline.called)
    }
}
