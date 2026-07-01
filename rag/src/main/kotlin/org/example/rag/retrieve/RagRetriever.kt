package org.example.rag.retrieve

/**
 * Retrieval-only seam (no generative LLM): a question in, ranked chunks out. The generator lives in
 * `:app`. [DefaultRagRetriever] is the impl; the interface keeps `:app` decoupled and makes the
 * retriever fakeable in tests.
 */
fun interface RagRetriever {
    /**
     * Retrieve for [question], searching [topK] candidates. Returns the surviving chunks plus the
     * before/after counts (see [RetrievalResult]) — with the Day-23 improved pipeline, [topK] is the
     * wide retrieve-K and the filter cuts it down; the baseline leaves the counts equal.
     */
    suspend fun retrieve(question: String, topK: Int): RetrievalResult
}
