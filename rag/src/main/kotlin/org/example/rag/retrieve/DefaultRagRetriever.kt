package org.example.rag.retrieve

import org.example.rag.embed.Embedder
import org.example.rag.index.VectorIndex

/**
 * The retrieval pipeline as composable stages:
 *
 * ```
 * [queryTransformer] → embedder.embed → index.search(topK) → [reranker]
 * ```
 *
 * The middle two stages do the real work; the outer two are the Day-23 seats and default to identity
 * ([NoOpQueryTransformer], [NoOpReranker]) for the Day-22 baseline. Day 23 injects an LLM query
 * rewriter (from `:app`) and a [ThresholdReranker]. No LLM here — retrieval only.
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

    override suspend fun retrieve(question: String, topK: Int): RetrievalResult {
        val rewritten = queryTransformer.transform(question)
        val queryVector = embedder.embed(rewritten)
        val hits = index.search(queryVector, topK) // candidates before filtering
        val kept = reranker.rerank(question, hits) // after the relevance filter
        return RetrievalResult(kept, retrievedCount = hits.size)
    }
}
