package org.example.rag.retrieve

import org.example.rag.index.SearchResult

/**
 * The outcome of one retrieval, carrying the before/after counts the Day-23 comparison needs.
 *
 * @param results the chunks that survived the pipeline (after the [Reranker] filter), highest score first.
 * @param retrievedCount how many candidates the vector search returned *before* filtering (the
 *   "top-K before filtering"). Baseline retrieval (NoOp reranker) leaves this equal to [keptCount],
 *   so the filter's effect is visible only in the improved pipeline.
 */
data class RetrievalResult(
    val results: List<SearchResult>,
    val retrievedCount: Int,
) {
    /** How many survived the filter (the "top-K after filtering"). */
    val keptCount: Int get() = results.size
}
