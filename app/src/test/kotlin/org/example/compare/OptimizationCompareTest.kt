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

/**
 * [Day 29] Exercises the optimization runner's before/after collection fully offline: the SAME question run
 * through two profiles (the current prompt vs the tuned prompt) over a fake retriever, each wrapped in a
 * [MeasuringLlmClient] on a scripted clock. Verifies each profile's row captures its own answer, tokens,
 * and elapsed time; that the tuned system prompt actually REACHES the model on the optimized run (the
 * Day-29 [RagResponder] prompt param is wired); and that [summarize] reports avg output tokens.
 */
class OptimizationCompareTest {

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

    private fun responder(llm: LlmClient, systemPrompt: String) =
        RagResponder(llm, RagConfig(topK = 5), retrieverFactory = { _, _ -> retriever }, systemPrompt = systemPrompt)

    @Test
    fun `each profile captures its own answer and timing, and the tuned prompt reaches the optimized run`() {
        val default = RecordingLlmClient(structuredJson = """{"answer":"default answer","citations":[],"dont_know":false}""")
        val optimized = RecordingLlmClient(structuredJson = """{"answer":"concise answer","citations":[],"dont_know":false}""")
        val defaultMeasured = MeasuringLlmClient(default, TestClock(0, 5_000_000)::next)
        val optimizedMeasured = MeasuringLlmClient(optimized, TestClock(0, 2_000_000)::next)

        val runs = runBlocking {
            listOf(
                ProviderRun(
                    "default",
                    listOf(collectMetric("default", defaultMeasured, responder(defaultMeasured, RagResponder.RAG_SYSTEM), question)),
                ),
                ProviderRun(
                    "optimized",
                    listOf(
                        collectMetric(
                            "optimized",
                            optimizedMeasured,
                            responder(optimizedMeasured, RagResponder.RAG_SYSTEM_OPTIMIZED),
                            question,
                        ),
                    ),
                ),
            )
        }

        val before = runs[0].metrics.single()
        val after = runs[1].metrics.single()
        assertEquals("default answer", before.answer)
        assertEquals("concise answer", after.answer)
        assertEquals(5, before.elapsedMs)
        assertEquals(2, after.elapsedMs)
        assertEquals(3, after.outputTokens) // tokens flow from the model through the metric row

        // The Day-29 prompt swap actually reaches the model: default → RAG_SYSTEM, optimized → tuned prompt.
        assertEquals(RagResponder.RAG_SYSTEM, default.systemPrompt)
        assertEquals(RagResponder.RAG_SYSTEM_OPTIMIZED, optimized.systemPrompt)

        // The summary surfaces avg output tokens — the conciseness signal the runner prints.
        assertEquals(3, summarize("optimized", runs[1].metrics).avgOutputTokens)
    }
}
