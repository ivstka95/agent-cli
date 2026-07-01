package org.example.rag.retrieve

import org.example.rag.index.SearchResult

/**
 * Last stage of the retrieval pipeline: reorder/filter the vector-search hits before they are returned.
 *
 * Day-23 seat. In Day 22 the only impl is [NoOpReranker] (identity) — the stage is wired but returns
 * the hits unchanged, so a cross-encoder reranker or relevance filter can drop in here without
 * touching the retriever.
 */
fun interface Reranker {
    fun rerank(query: String, results: List<SearchResult>): List<SearchResult>
}

/** Identity rerank — returns the hits unchanged. The Day-22 default. */
object NoOpReranker : Reranker {
    override fun rerank(query: String, results: List<SearchResult>): List<SearchResult> = results
}
