package org.example.ragmode

import org.example.agent.LlmClient
import org.example.agent.Message
import org.example.agent.Role
import org.example.rag.retrieve.QueryTransformer

/**
 * [Day 23] The LLM-backed [QueryTransformer] — fills the `:rag` query-rewrite seat while keeping the
 * generative LLM in `:app`. It rephrases/expands the user's question (adding technical terms, class
 * names, synonyms) so the embedded query lands nearer the relevant code chunks — e.g. "how are the
 * indexes stored?" expands toward "JsonVectorIndex JSON serialization on disk", surfacing the file a
 * bare query missed.
 *
 * The interface lives in `:rag` (a pure seam); this implementation is injected into the retriever by
 * [RagResponder.fromConfig], so `:rag` never imports an LLM client.
 *
 * Robustness: a blank rewrite or any LLM error falls back to the original query — a failed rewrite
 * must never break retrieval.
 */
class LlmQueryRewriter(private val llmClient: LlmClient) : QueryTransformer {

    override suspend fun transform(query: String): String =
        try {
            val result = llmClient.complete(REWRITE_SYSTEM, listOf(Message(Role.USER, query)))
            result.replyText.trim().ifBlank { query }
        } catch (_: Exception) {
            query // a rewrite failure must not break retrieval
        }

    companion object {
        const val REWRITE_SYSTEM =
            "You rewrite a user's question to improve retrieval from a Kotlin codebase's vector index. " +
                "Rephrase and expand it with relevant technical terms, likely class/file names, and synonyms " +
                "so it matches source code and docs more closely. Keep it in English and concise. " +
                "Output ONLY the rewritten query — no preamble, no quotes, no explanation."
    }
}
