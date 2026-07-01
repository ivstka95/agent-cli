package org.example.rag.retrieve

import org.example.rag.index.SearchResult

/**
 * Retrieval-only seam (no generative LLM): a question in, ranked chunks out. The generator lives in
 * `:app`. [DefaultRagRetriever] is the Day-22 impl; the interface keeps `:app` decoupled and makes the
 * retriever fakeable in tests.
 */
fun interface RagRetriever {
    /** The [topK] chunks most relevant to [question], highest score first. */
    suspend fun retrieve(question: String, topK: Int): List<SearchResult>
}
