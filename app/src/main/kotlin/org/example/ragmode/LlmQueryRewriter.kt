package org.example.ragmode

import org.example.agent.LlmClient
import org.example.agent.Message
import org.example.agent.Role
import org.example.rag.retrieve.QueryTransformer

/**
 * [Day 23] The LLM-backed [QueryTransformer] — fills the `:rag` query-rewrite seat while keeping the
 * generative LLM in `:app`. Query rewrite helps *vague* queries (short, conversational, missing the
 * corpus's technical terms) by clarifying intent and adding a key missing term. It does NOT help a
 * query that is already precise: expanding an already-good query with synonyms pushes its embedding
 * *away* from the target chunk and lowers cosine scores. So the prompt tells the model to leave
 * specific queries unchanged and only rewrite vague ones — the model judges, no brittle code heuristic.
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
            "You rewrite a user's question ONLY when doing so will improve retrieval from a Kotlin " +
                "codebase's vector index. If the query is already specific and uses the right technical " +
                "terms (class names, precise concepts), return it unchanged or nearly unchanged. Only " +
                "rewrite vague, short, or conversational queries: clarify the intent and add a key missing " +
                "technical term ONLY when it is clearly absent. Do NOT pad with synonyms or expand a query " +
                "that is already precise — that pushes it away from the target and hurts retrieval. Keep it " +
                "in English and concise. Output ONLY the query — no preamble, no quotes, no explanation."
    }
}
