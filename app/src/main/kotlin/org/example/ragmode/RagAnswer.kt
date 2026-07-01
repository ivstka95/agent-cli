package org.example.ragmode

/**
 * One RAG-mode answer. [sources] is the deterministic list of `file:section` labels taken from the
 * retrieved chunks' metadata (empty for the without-RAG baseline); it is already reflected in [answer]
 * as a trailing `Sources: [...]` line, but is kept structured for the comparison runner.
 */
data class RagAnswer(
    val answer: String,
    val sources: List<String>,
    val inputTokens: Int,
    val outputTokens: Int,
)
