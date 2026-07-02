package org.example.rag.retrieve

import org.example.rag.index.SearchResult

/**
 * Last stage of the retrieval pipeline: reorder/filter the vector-search hits before they are returned.
 *
 * Day-23 seat. In Day 22 the only impl is [NoOpReranker] (identity) — the stage is wired but returns
 * the hits unchanged. Day 23 fills it with [ThresholdReranker], a similarity-threshold relevance
 * filter (Ollama exposes no cross-encoder rerank endpoint, so we use the cosine `score` we already
 * have). A true cross-encoder reranker could drop in here later without touching the retriever.
 */
fun interface Reranker {
    fun rerank(query: String, results: List<SearchResult>): List<SearchResult>
}

/** Identity rerank — returns the hits unchanged. The Day-22 default. */
object NoOpReranker : Reranker {
    override fun rerank(query: String, results: List<SearchResult>): List<SearchResult> = results
}
