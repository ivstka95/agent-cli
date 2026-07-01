package org.example.rag.retrieve

/**
 * First stage of the retrieval pipeline: rewrite/expand the raw question before it is embedded.
 *
 * Day-23 seat. In Day 22 the only impl is [NoOpQueryTransformer] (identity) — the stage is wired but
 * passes the query through unchanged, so query rewrite can drop in here without touching the retriever.
 */
fun interface QueryTransformer {
    fun transform(query: String): String
}

/** Identity transform — returns the query unchanged. The Day-22 default. */
object NoOpQueryTransformer : QueryTransformer {
    override fun transform(query: String): String = query
}
