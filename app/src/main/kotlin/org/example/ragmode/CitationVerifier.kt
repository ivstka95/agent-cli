package org.example.ragmode

/**
 * [Day 24] Cheap anti-hallucination check for citations: a citation quote is trustworthy only if it was
 * actually COPIED from one of the retrieved chunks. We verify by whitespace-normalized substring match —
 * this directly proves the model didn't invent or paraphrase the fragment.
 *
 * Only whitespace is normalized (collapsed runs of spaces/newlines/tabs to a single space, trimmed);
 * the match stays case-sensitive, so a "verbatim" quote is a true verbatim copy modulo reflowing.
 */
object CitationVerifier {

    /** True if [quote] appears (whitespace-normalized) inside any of the retrieved [chunkTexts]. */
    fun isVerbatim(quote: String, chunkTexts: List<String>): Boolean {
        val needle = normalizeWs(quote)
        if (needle.isEmpty()) return false
        return chunkTexts.any { normalizeWs(it).contains(needle) }
    }

    /** Collapse all whitespace runs to a single space and trim, so reflowed quotes still match. */
    fun normalizeWs(text: String): String = text.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")
}
