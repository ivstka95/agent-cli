package org.example.rag

import kotlinx.coroutines.runBlocking
import org.example.rag.index.JsonVectorIndex
import org.example.rag.index.SearchResult
import org.example.rag.model.Chunk
import org.example.rag.model.ChunkMetadata
import org.example.rag.retrieve.DefaultRagRetriever
import org.example.rag.retrieve.NoOpQueryTransformer
import org.example.rag.retrieve.NoOpReranker
import org.example.rag.retrieve.QueryTransformer
import org.example.rag.retrieve.Reranker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultRagRetrieverTest {

    private val embedder = FakeEmbedder(dim = 8)

    private fun chunk(ordinal: Int, text: String): Chunk =
        Chunk(
            text = text,
            metadata = ChunkMetadata(
                source = "docs/sample.md",
                file = "sample.md",
                section = "s$ordinal",
                chunkId = "docs/sample.md#structural#$ordinal",
                strategy = "structural",
                ordinal = ordinal,
            ),
        )

    private fun index(vararg chunks: Chunk): JsonVectorIndex = runBlocking {
        JsonVectorIndex(model = "fake", dimension = 8, strategy = "structural").apply {
            chunks.forEach { add(it, embedder.embed(it.text)) }
        }
    }

    @Test
    fun `retrieve ranks the matching chunk first and respects topK`() = runBlocking {
        val chunks = arrayOf(
            chunk(0, "alpha content about cats"),
            chunk(1, "beta content about dogs"),
            chunk(2, "gamma content about birds"),
        )
        val retriever = DefaultRagRetriever(embedder, index(*chunks))

        val hits = retriever.retrieve("beta content about dogs", topK = 2)

        assertEquals(2, hits.size) // topK respected
        assertEquals(chunks[1].id, hits.first().chunk.id) // same text ⇒ score ~1 ⇒ ranked first
        assertEquals(1.0f, hits.first().score, 1e-4f)
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score }) // non-increasing
    }

    @Test
    fun `passthrough stages are identity - query and hits pass through unchanged`() = runBlocking {
        assertEquals("How does routing work?", NoOpQueryTransformer.transform("How does routing work?"))
        val hits = listOf(SearchResult(chunk(0, "x"), 0.9f), SearchResult(chunk(1, "y"), 0.5f))
        assertEquals(hits, NoOpReranker.rerank("q", hits))
    }

    @Test
    fun `custom stages are invoked in order - transform feeds embed, rerank post-processes`() = runBlocking {
        val chunks = arrayOf(chunk(0, "cats"), chunk(1, "dogs"))
        // Transformer rewrites the query to chunk 0's text so chunk 0 ranks first;
        // reranker then reverses, proving it runs after search.
        val transformer = QueryTransformer { "cats" }
        val reranker = Reranker { _, results -> results.reversed() }
        val retriever = DefaultRagRetriever(embedder, index(*chunks), transformer, reranker)

        val hits = retriever.retrieve("unrelated question", topK = 2)

        assertEquals(2, hits.size)
        // Search ranked chunk 0 ("cats") first; reranker reversed it to last.
        assertEquals(chunks[1].id, hits.first().chunk.id)
        assertEquals(chunks[0].id, hits.last().chunk.id)
    }
}
