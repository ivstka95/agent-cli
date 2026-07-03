package org.example.ragmode

/**
 * A single citation [Day 24]: a [quote] the model copied VERBATIM from a retrieved context block plus
 * the [source] (`file:section`) of the block it came from. [verbatim] records whether the quote was
 * actually found (whitespace-normalized) inside some retrieved chunk's text — the cheap
 * anti-hallucination check ([CitationVerifier.isVerbatim]); a false value means the model fabricated or
 * paraphrased the quote rather than copying it.
 */
data class Citation(
    val quote: String,
    val source: String,
    val verbatim: Boolean,
)

/**
 * One RAG-mode answer. [sources] is the deterministic list of `file:section` labels taken from the
 * retrieved chunks' metadata (empty for the without-RAG baseline) — the authoritative, always-present
 * sources list, independent of what the model chose to cite.
 *
 * [Day 24] [citations] are the model's VERBATIM fragments (quote + per-quote source), each tagged with
 * whether it really appears in a retrieved chunk; [dontKnow] is set when the model judged the context
 * does NOT answer the question and [answer] is its "I don't know — please clarify" ask instead of an
 * invented answer. On a normal grounded answer [citations] is non-empty and [dontKnow] is false.
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
    val citations: List<Citation> = emptyList(),
    val dontKnow: Boolean = false,
) {
    /** Chunks kept after filtering (the "after" count) — one scored entry per kept chunk. */
    val keptAfter: Int get() = scoredSources.size

    /** [Day 24] Whether the model backed the answer with at least one citation. */
    val hasCitations: Boolean get() = citations.isNotEmpty()

    /** [Day 24] The Day-24 anti-hallucination check: every citation is a real verbatim quote. */
    val allCitationsVerbatim: Boolean get() = hasCitations && citations.all { it.verbatim }
}
