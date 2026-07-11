package org.example.compare

import kotlinx.coroutines.runBlocking
import org.example.agent.LlmClient
import org.example.ragmode.RagResponder
import org.example.ragmode.RecordingLlmClient
import org.example.rag.config.RagConfig
import org.example.rag.index.SearchResult
import org.example.rag.model.Chunk
import org.example.rag.model.ChunkMetadata
import org.example.rag.retrieve.RagRetriever
import org.example.rag.retrieve.RetrievalResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the runner's metric-collection path fully offline: a real [RagResponder] over a fake
 * retriever, with each provider wrapped in a [MeasuringLlmClient] on a scripted clock. Verifies that
 * elapsed, structured-valid, and sources-present are captured, and that the summary tallies are correct —
 * including a single-provider (cloud-absent) run.
 */
class MetricCollectionTest {

    private val question = CompareQuestion(difficulty = "simple", question = "What model?")

    private val hits = listOf(
        SearchResult(
            Chunk(
                text = "the RAG module uses nomic-embed-text",
                metadata = ChunkMetadata(
                    source = "rag/src/RagConfig.kt",
                    file = "RagConfig.kt",
                    section = "fromEnv",
                    chunkId = "rag/src/RagConfig.kt#structural#0",
                    strategy = "structural",
                    ordinal = 0,
                ),
            ),
            score = 0.9f,
        ),
    )

    private val retriever = RagRetriever { _, _ -> RetrievalResult(hits, retrievedCount = hits.size) }

    private fun responder(llm: LlmClient) =
        RagResponder(llm, RagConfig(topK = 5), retrieverFactory = { _, _ -> retriever })

    @Test
    fun `a fast provider with valid JSON is captured as valid, sourced, and timed`() {
        // One structured call that parses → 2 clock reads → 2 ms.
        val measuring = MeasuringLlmClient(
            RecordingLlmClient(structuredJson = """{"answer":"nomic-embed-text","citations":[],"dont_know":false}"""),
            TestClock(0, 2_000_000)::next,
        )

        val metric = runBlocking { collectMetric("local", measuring, responder(measuring), question) }

        assertEquals("local", metric.provider)
        assertEquals("simple", metric.difficulty)
        assertEquals(2, metric.elapsedMs)
        assertTrue(metric.structuredValid)
        assertTrue(metric.sourcesPresent)
        assertFalse(metric.dontKnow)
        assertEquals("nomic-embed-text", metric.answer)
    }

    @Test
    fun `a slow provider whose JSON never parses is captured as invalid but still sourced`() {
        // Unparseable payload → responder retries once → 2 structured calls → 4 clock reads → 30 + 20 = 50 ms.
        val measuring = MeasuringLlmClient(
            RecordingLlmClient(structuredJson = "not valid json"),
            TestClock(0, 30_000_000, 30_000_000, 50_000_000)::next,
        )

        val metric = runBlocking { collectMetric("cloud", measuring, responder(measuring), question) }

        assertEquals(50, metric.elapsedMs)
        assertFalse(metric.structuredValid) // both attempts failed → responder fell back
        assertTrue(metric.sourcesPresent) // deterministic sources survive the parse failure
    }

    @Test
    fun `summary tallies over both providers`() {
        val fast = QuestionMetric("local", "simple", "q", "a", 2, 7, 3, structuredValid = true, sourcesPresent = true, dontKnow = false)
        val slow = QuestionMetric("cloud", "simple", "q", "a", 50, 14, 6, structuredValid = false, sourcesPresent = true, dontKnow = false)

        val local = summarize("local", listOf(fast))
        val both = summarize("all", listOf(fast, slow))

        assertEquals(2, local.avgElapsedMs)
        assertEquals(26, both.avgElapsedMs) // (2 + 50) / 2
        assertEquals(1, both.structuredValidCount)
        assertEquals(2, both.sourcesPresentCount)
    }

    @Test
    fun `a single-provider run summarizes local-only when cloud is absent`() {
        val measuring = MeasuringLlmClient(RecordingLlmClient(), TestClock(0, 4_000_000)::next)

        val run = runBlocking { ProviderRun("local", listOf(collectMetric("local", measuring, responder(measuring), question))) }
        val summary = summarize(run.label, run.metrics)

        assertEquals(1, summary.n)
        assertEquals(4, summary.avgElapsedMs)
        assertEquals(1, summary.structuredValidCount)
        assertEquals(1, summary.sourcesPresentCount)
    }
}
