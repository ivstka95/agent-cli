package org.example.ragmode

/**
 * One RAG-mode answer. [sources] is the deterministic list of `file:section` labels taken from the
 * retrieved chunks' metadata (empty for the without-RAG baseline); it is already reflected in [answer]
 * as a trailing `Sources: [...]` line, but is kept structured for the comparison runner.
 *
 * [scoredSources] lists the surviving chunks as `file:section (0.63)` (one per kept chunk, not
 * de-duplicated) so the eval can print cosine scores — useful for calibrating `RAG_SCORE_THRESHOLD`.
 *
 * [retrievedBefore]/[keptAfter] are the [Day 23] before/after retrieved counts — how many candidates
 * the vector search returned vs. how many survived the relevance filter. Both are 0 when no retrieval
 * happened (the without-RAG path); equal when no filter ran (the baseline RAG path); [keptAfter] <
 * [retrievedBefore] shows the filter's effect in the improved pipeline.
 */
data class RagAnswer(
    val answer: String,
    val sources: List<String>,
    val inputTokens: Int,
    val outputTokens: Int,
    val retrievedBefore: Int = 0,
    val scoredSources: List<String> = emptyList(),
) {
    /** Chunks kept after filtering (the "after" count) — one scored entry per kept chunk. */
    val keptAfter: Int get() = scoredSources.size
}
