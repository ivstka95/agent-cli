package org.example.rag.retrieve

/**
 * First stage of the retrieval pipeline: rewrite/expand the raw question before it is embedded.
 *
 * [transform] is `suspend` because the Day-23 impl rewrites the query with an LLM (which lives in
 * `:app`, not here — `:rag` stays free of any generative-LLM dependency). The interface stays in
 * `:rag` as a pure seam; the LLM-backed implementation lives in `:app` and is injected into the
 * retriever. In Day 22 the only impl is [NoOpQueryTransformer] (identity, passthrough).
 */
fun interface QueryTransformer {
    suspend fun transform(query: String): String
}

/** Identity transform — returns the query unchanged. The Day-22 default. */
object NoOpQueryTransformer : QueryTransformer {
    override suspend fun transform(query: String): String = query
}
