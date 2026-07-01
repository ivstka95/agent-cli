package org.example.rag

import org.example.rag.index.SearchResult
import org.example.rag.model.Chunk
import org.example.rag.model.ChunkMetadata
import org.example.rag.retrieve.ThresholdReranker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThresholdRerankerTest {

    private fun hit(ordinal: Int, score: Float): SearchResult =
        SearchResult(
            Chunk(
                text = "chunk $ordinal",
                metadata = ChunkMetadata(
                    source = "docs/sample.md",
                    file = "sample.md",
                    section = "s$ordinal",
                    chunkId = "docs/sample.md#structural#$ordinal",
                    strategy = "structural",
                    ordinal = ordinal,
                ),
            ),
            score,
        )

    // Descending by score, as JsonVectorIndex.search returns them.
    private val hits = listOf(hit(0, 0.90f), hit(1, 0.70f), hit(2, 0.55f), hit(3, 0.40f), hit(4, 0.20f))

    @Test
    fun `drops hits below the threshold and keeps those at or above`() {
        val kept = ThresholdReranker(minScore = 0.55f, keepTopK = 10).rerank("q", hits)

        // 0.90, 0.70, 0.55 survive; 0.40 and 0.20 are dropped. Cutoff is inclusive.
        assertEquals(listOf(0, 1, 2), kept.map { it.chunk.metadata.ordinal })
    }

    @Test
    fun `caps the survivors at keepTopK, keeping the highest scores`() {
        val kept = ThresholdReranker(minScore = 0.0f, keepTopK = 2).rerank("q", hits)

        assertEquals(2, kept.size)
        assertEquals(listOf(0, 1), kept.map { it.chunk.metadata.ordinal }) // top-2 by score
    }

    @Test
    fun `returns empty when every hit is below the threshold`() {
        val kept = ThresholdReranker(minScore = 0.99f, keepTopK = 5).rerank("q", hits)

        assertTrue(kept.isEmpty())
    }

    @Test
    fun `preserves the incoming score order`() {
        val kept = ThresholdReranker(minScore = 0.3f, keepTopK = 5).rerank("q", hits)

        assertTrue(kept.zipWithNext().all { (a, b) -> a.score >= b.score })
    }
}
