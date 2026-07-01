package org.example.rag.retrieve

import org.example.rag.embed.Embedder
import org.example.rag.index.SearchResult
import org.example.rag.index.VectorIndex

/**
 * The Day-22 retrieval pipeline as composable stages:
 *
 * ```
 * [queryTransformer] → embedder.embed → index.search(topK) → [reranker]
 * ```
 *
 * The middle two stages do the real work; the outer two are Day-23 seats and default to identity
 * ([NoOpQueryTransformer], [NoOpReranker]). No LLM here — retrieval only.
 *
 * The [index] is loaded once (e.g. via `JsonVectorIndex.load`) and queried repeatedly; the [embedder]
 * MUST be the same model used to build the index, or scores are meaningless.
 */
class DefaultRagRetriever(
    private val embedder: Embedder,
    private val index: VectorIndex,
    private val queryTransformer: QueryTransformer = NoOpQueryTransformer,
    private val reranker: Reranker = NoOpReranker,
) : RagRetriever {

    override suspend fun retrieve(question: String, topK: Int): List<SearchResult> {
        val rewritten = queryTransformer.transform(question)
        val queryVector = embedder.embed(rewritten)
        val hits = index.search(queryVector, topK)
        return reranker.rerank(question, hits)
    }
}
