package org.example.rag.retrieve

import org.example.rag.index.SearchResult

/**
 * Day-23 relevance filter filling the [Reranker] seat: drops hits whose cosine [SearchResult.score]
 * is below [minScore], then keeps the top [keepTopK]. Pure Kotlin — no model call, no IO.
 *
 * This is the "second stage after search" for Day 23. Retrieval casts a wide net
 * (`search(retrieveK)` with a large K), and this filter cuts the low-relevance tail so only the
 * strongest chunks reach the generator. Hits arrive already sorted by descending score
 * (`JsonVectorIndex.search`), so [take] keeps the best [keepTopK] after the cutoff.
 *
 * A true cross-encoder reranker isn't usable through Ollama (embedding layer only, no rerank head),
 * so the task's allowed "similarity threshold / heuristic" is used instead.
 *
 * @param minScore inclusive cosine cutoff — hits with `score < minScore` are dropped.
 * @param keepTopK maximum hits to keep after filtering (the "top-K after filtering").
 */
class ThresholdReranker(
    private val minScore: Float,
    private val keepTopK: Int,
) : Reranker {
    override fun rerank(query: String, results: List<SearchResult>): List<SearchResult> =
        results.filter { it.score >= minScore }.take(keepTopK)
}
