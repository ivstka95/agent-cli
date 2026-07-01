package org.example.ragmode

import kotlinx.coroutines.runBlocking
import org.example.llm.AnthropicClient
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
 * Requires `ANTHROPIC_API_KEY`, a running Ollama, and the index built (`./gradlew :rag:runIndexer`).
 * Config via [RagConfig.fromEnv] (RAG_INDEX_STRATEGY, RAG_TOP_K, RAG_RETRIEVE_K, RAG_AFTER_K,
 * RAG_SCORE_THRESHOLD, OLLAMA_*).
 */
fun main() = runBlocking {
    val llmClient = try {
        AnthropicClient()
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

    try {
        questions.forEachIndexed { i, q ->
            // Improved first so a missing index fails fast (before any baseline LLM call).
            val improved = responder.answer(q.question, useRag = true, improved = true)
            val baseline = responder.answer(q.question, useRag = true, improved = false)

            println("\nQ${i + 1}. ${q.question}")
            println("-".repeat(100))
            println("[BASELINE] retrieved ${baseline.retrievedBefore} → kept ${baseline.keptAfter}")
            println(indent(baseline.answer))
            println("  sources: ${sourcesLine(baseline)}")
            println("\n[IMPROVED] retrieved ${improved.retrievedBefore} → kept ${improved.keptAfter} (rewrite + threshold filter)")
            println(indent(improved.answer))
            println("  sources: ${sourcesLine(improved)}")
            println("\n[EXPECTATION] ${q.expectation}")
            println("[EXPECTED SOURCES] ${q.expectedSources.joinToString(", ")}")
            println("=".repeat(100))
        }
    } finally {
        responder.close()
    }
}

/** Retrieved sources with scores, or `(none)` when nothing survived retrieval. */
private fun sourcesLine(answer: RagAnswer): String =
    if (answer.scoredSources.isEmpty()) "(none)" else answer.scoredSources.joinToString(", ")

private fun indent(text: String): String = text.lineSequence().joinToString("\n") { "  $it" }
