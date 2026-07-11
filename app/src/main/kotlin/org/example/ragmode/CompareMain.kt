package org.example.ragmode

import kotlinx.coroutines.runBlocking
import org.example.llm.LlmClientFactory
import org.example.rag.config.RagConfig
import kotlin.system.exitProcess

/**
 * [Day 23] RAG evaluation runner — a SEPARATE run mode from the interactive REPL (`org.example.MainKt`),
 * mirroring how `runDigest` separates the digest daemon. It runs each of the 10 English control
 * questions through BOTH RAG pipelines side by side:
 *
 *  - BASELINE — the Day-22 pipeline: `search(topK)`, no query rewrite, no relevance filter.
 *  - IMPROVED — the Day-23 pipeline: LLM query rewrite → `search(retrieveK)` (wide net) → similarity
 *    threshold filter down to `afterK`.
 *
 * and prints, per question, both answers, the before→after retrieved counts (so the filter's effect
 * is visible), the retrieved sources with cosine scores (to calibrate the threshold), and the
 * expectation + expected sources (the eval set). The comparison shows whether rewrite + wide-net +
 * filtering surfaces more relevant chunks than the baseline (e.g. Q9 → JsonVectorIndex.kt).
 *
 * [Day 24] Each answer is now a structured `{answer, citations, dont_know}` call. Per question we also
 * print the model's VERBATIM citations (quote + source) and three automatic ✓/✗ checks — sources
 * present, citations present, and every citation verbatim (a real substring of a retrieved chunk) —
 * plus a final tally over the improved pipeline. Answer-meaning-vs-citations is judged BY EYE (the
 * answer prints right next to its citations); there is no LLM judge. A `dont_know` answer is marked
 * distinctly — the point is the model CAN refuse when the context doesn't answer (e.g. an off-topic
 * question, or the Q9 weak-retrieval miss).
 *
 * Requires `ANTHROPIC_API_KEY`, a running Ollama, and the index built (`./gradlew :rag:runIndexer`).
 * Config via [RagConfig.fromEnv] (RAG_INDEX_STRATEGY, RAG_TOP_K, RAG_RETRIEVE_K, RAG_AFTER_K,
 * RAG_SCORE_THRESHOLD, OLLAMA_*).
 */
fun main() = runBlocking {
    // [Day 27] LLM_PROVIDER (anthropic|ollama, default anthropic) picks the backend; the eval runs
    // whole through it. CLOUD still fails fast on a missing key; ollama runs the eval fully offline.
    val llmClient = try {
        LlmClientFactory.fromEnv()
    } catch (e: IllegalStateException) {
        System.err.println(e.message)
        exitProcess(1)
    }

    val config = RagConfig.fromEnv()
    val responder = RagResponder.fromConfig(llmClient, config)

    val questions = ControlQuestion.loadEvalSet()
    println(
        "RAG evaluation — ${questions.size} questions · index: ${config.indexStrategy.fileName} · " +
            "baseline top-K: ${config.topK} · improved: rewrite + retrieveK=${config.retrieveK} → " +
            "threshold ${config.scoreThreshold} → afterK=${config.afterK}",
    )
    println("=".repeat(100))

    // [Day 24] Collect the improved-pipeline answers so the automatic checks can be tallied at the end.
    val improvedAnswers = mutableListOf<RagAnswer>()

    try {
        questions.forEachIndexed { i, q ->
            // Improved first so a missing index fails fast (before any baseline LLM call).
            val improved = responder.answer(q.question, useRag = true, improved = true)
            val baseline = responder.answer(q.question, useRag = true, improved = false)
            improvedAnswers += improved

            println("\nQ${i + 1}. ${q.question}")
            println("-".repeat(100))
            printAnswer("BASELINE", "", baseline)
            printAnswer("IMPROVED", " (rewrite + threshold filter)", improved)
            println("\n[EXPECTATION] ${q.expectation}")
            println("[EXPECTED SOURCES] ${q.expectedSources.joinToString(", ")}")
            println("=".repeat(100))
        }
    } finally {
        responder.close()
    }

    val n = questions.size
    println("\nSUMMARY (improved pipeline over $n questions)")
    println(
        "  sources present: ${improvedAnswers.count { it.sources.isNotEmpty() }}/$n · " +
            "citations present: ${improvedAnswers.count { it.hasCitations }}/$n · " +
            "all citations verbatim: ${improvedAnswers.count { it.allCitationsVerbatim }}/$n · " +
            "\"I don't know\": ${improvedAnswers.count { it.dontKnow }}/$n",
    )
}

/** Prints one pipeline's structured answer: counts, the answer (or dont-know), citations, and ✓/✗ checks. */
private fun printAnswer(name: String, suffix: String, answer: RagAnswer) {
    val header = "retrieved ${answer.retrievedBefore} → kept ${answer.keptAfter}$suffix"
    println("\n[$name] $header${if (answer.dontKnow) "  — I DON'T KNOW" else ""}")
    println(indent(answer.answer))
    println("  sources: ${sourcesLine(answer)}")
    println("  citations:")
    if (answer.citations.isEmpty()) {
        println("    (none)")
    } else {
        answer.citations.forEach { c ->
            val mark = if (c.verbatim) "verbatim" else "UNVERIFIED"
            println("    [$mark] \"${c.quote}\"  — ${c.source}")
        }
    }
    println("  checks: ${checks(answer)}")
}

/** The three automatic Day-24 checks as ✓/✗: sources present, citations present, all citations verbatim. */
private fun checks(answer: RagAnswer): String {
    fun mark(ok: Boolean) = if (ok) "✓" else "✗"
    return "sources ${mark(answer.sources.isNotEmpty())} · " +
        "citations ${mark(answer.hasCitations)} · verbatim ${mark(answer.allCitationsVerbatim)}"
}

/** Retrieved sources with scores, or `(none)` when nothing survived retrieval. */
private fun sourcesLine(answer: RagAnswer): String =
    if (answer.scoredSources.isEmpty()) "(none)" else answer.scoredSources.joinToString(", ")

private fun indent(text: String): String = text.lineSequence().joinToString("\n") { "  $it" }
