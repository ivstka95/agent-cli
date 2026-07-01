package org.example.rag

import kotlinx.coroutines.runBlocking
import org.example.rag.index.JsonVectorIndex
import org.example.rag.model.Chunk
import org.example.rag.model.ChunkMetadata
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonVectorIndexTest {

    private val embedder = FakeEmbedder(dim = 8)

    private fun chunk(ordinal: Int, text: String): Chunk =
        Chunk(
            text = text,
            metadata = ChunkMetadata(
                source = "docs/sample.md",
                file = "sample.md",
                section = null,
                chunkId = "docs/sample.md#fixed-size#$ordinal",
                strategy = "fixed-size",
                ordinal = ordinal,
            ),
        )

    @Test
    fun `saves and loads a round-trip, and search ranks the matching chunk first`() = runBlocking {
        val chunks = listOf(
            chunk(0, "alpha content about cats"),
            chunk(1, "beta content about dogs"),
            chunk(2, "gamma content about birds"),
        )

        val index = JsonVectorIndex(model = "fake", dimension = 8, strategy = "fixed-size")
        chunks.forEach { index.add(it, embedder.embed(it.text)) }

        val file = File(createTempDirectory("rag-index-test").toFile(), "index-fixed-size.json")
        index.save(file)
        assertTrue(file.exists() && file.readText().contains("\"entries\""))

        val loaded = JsonVectorIndex.load(file)
        assertEquals(chunks.size, loaded.size)

        // Re-embedding chunk 1's text and searching the LOADED index must return chunk 1 first with
        // score ~1 (identical unit vectors) — proving vectors + metadata survived the round-trip.
        val hits = loaded.search(embedder.embed(chunks[1].text), topK = 3)
        assertEquals(chunks.size, hits.size)
        assertEquals(chunks[1].id, hits.first().chunk.id)
        assertEquals(chunks[1].metadata, hits.first().chunk.metadata)
        assertEquals(1.0f, hits.first().score, 1e-4f)
        // Ranked: scores are non-increasing.
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
    }
}
