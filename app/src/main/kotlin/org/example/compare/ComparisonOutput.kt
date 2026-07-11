package org.example.compare

/**
 * [Day 28/29] Shared side-by-side output for the comparison runners (`runLocalVsCloud`,
 * `runOptimizationCompare`). The per-question metric row is one format contract, so both runners print it
 * the same way; each runner keeps its OWN summary (they report different aggregates). IO-only, no logic.
 */

/** Prints each question with every run's answer + metrics stacked beneath it, in the order given. */
internal fun printSideBySide(questions: List<CompareQuestion>, runs: List<ProviderRun>) {
    questions.forEachIndexed { i, q ->
        println("\nQ${i + 1} [${q.difficulty}] ${q.question}")
        println("-".repeat(100))
        runs.forEach { run -> printMetric(run.metrics[i]) }
        println("=".repeat(100))
    }
}

/** One run's metric block for one question: the metrics line, then the answer (for by-eye quality). */
internal fun printMetric(m: QuestionMetric) {
    fun mark(ok: Boolean) = if (ok) "✓" else "✗"
    println(
        "\n[${m.provider.uppercase()}] ${m.elapsedMs} ms · tokens ${m.inputTokens} in / ${m.outputTokens} out · " +
            "structured ${mark(m.structuredValid)} · sources ${mark(m.sourcesPresent)}" +
            if (m.dontKnow) " · I DON'T KNOW" else "",
    )
    println(m.answer.lineSequence().joinToString("\n") { "  $it" })
}
