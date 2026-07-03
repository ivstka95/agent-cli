package org.example.ragmode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CitationVerifierTest {

    private val chunks = listOf(
        "The agentic loop chains tool calls until the model answers.",
        "JsonVectorIndex saves each index as rag-index/index-<strategy>.json.",
    )

    @Test
    fun `a substring quote is verbatim`() {
        assertTrue(CitationVerifier.isVerbatim("chains tool calls", chunks))
    }

    @Test
    fun `a non-substring quote is not verbatim`() {
        assertFalse(CitationVerifier.isVerbatim("streams tokens over a websocket", chunks))
    }

    @Test
    fun `a paraphrase is not verbatim`() {
        // Same meaning, different words — must be rejected.
        assertFalse(CitationVerifier.isVerbatim("the loop keeps calling tools", chunks))
    }

    @Test
    fun `whitespace differences still match after normalization`() {
        // Reflowed with newlines and extra spaces — normalizeWs collapses them, so it still matches.
        assertTrue(CitationVerifier.isVerbatim("chains   tool\n  calls", chunks))
    }

    @Test
    fun `an empty quote is not verbatim`() {
        assertFalse(CitationVerifier.isVerbatim("   ", chunks))
    }

    @Test
    fun `no chunks means nothing is verbatim`() {
        assertFalse(CitationVerifier.isVerbatim("chains tool calls", emptyList()))
    }
}
