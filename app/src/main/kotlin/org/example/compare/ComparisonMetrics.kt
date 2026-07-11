package org.example.compare

/**
 * [Day 28] One provider's result for one question — the row of the side-by-side comparison. [provider] is
 * `local` or `cloud`. Speed = [elapsedMs] (generation only); stability = [structuredValid] (did the
 * `{answer, citations, dont_know}` JSON parse, or did the responder fall back?); grounding =
 * [sourcesPresent] (retrieval surfaced sources). [answer] is printed so quality can be judged BY EYE.
 */
data class QuestionMetric(
    val provider: String,
    val difficulty: String,
    val question: String,
    val answer: String,
    val elapsedMs: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val structuredValid: Boolean,
    val sourcesPresent: Boolean,
    val dontKnow: Boolean,
)

/**
 * [Day 28] Aggregate of one provider's [QuestionMetric]s: average generation time (speed),
 * structured-valid count (stability), and sources-present count (grounding), over [n] questions.
 */
data class ProviderSummary(
    val label: String,
    val n: Int,
    val avgElapsedMs: Long,
    val structuredValidCount: Int,
    val sourcesPresentCount: Int,
    // [Day 29] Average output tokens — the conciseness signal the optimization runner reports (a tuned
    // prompt + `num_predict` cap should shorten answers). Day 28's summary line doesn't print it, so its
    // output is unchanged.
    val avgOutputTokens: Int,
)

/** Pure aggregation over one provider's metrics — kept IO-free so it is unit-tested directly. */
fun summarize(label: String, metrics: List<QuestionMetric>): ProviderSummary {
    val n = metrics.size
    val avg = if (n == 0) 0L else metrics.sumOf { it.elapsedMs } / n
    return ProviderSummary(
        label = label,
        n = n,
        avgElapsedMs = avg,
        structuredValidCount = metrics.count { it.structuredValid },
        sourcesPresentCount = metrics.count { it.sourcesPresent },
        avgOutputTokens = if (n == 0) 0 else metrics.sumOf { it.outputTokens } / n,
    )
}
