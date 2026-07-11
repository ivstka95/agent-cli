package org.example.compare

import kotlinx.coroutines.runBlocking
import org.example.agent.LlmClient
import org.example.llm.LlmClientFactory
import org.example.llm.Provider
import org.example.ragmode.RagResponder
import org.example.rag.config.RagConfig

/**
 * [Day 28] Local-vs-cloud RAG comparison runner — a SEPARATE run mode from the interactive REPL
 * (`org.example.MainKt`), mirroring `runRagEval` / `runDay25Eval`. It runs the SAME 3 questions (simple /
 * medium / hard) through the SAME RAG path on BOTH providers and prints them side by side with metrics:
 *
 *  - LOCAL  — Ollama generation (Day 27). Always run; fully offline, no API key.
 *  - CLOUD  — Anthropic generation. Run only if `ANTHROPIC_API_KEY` is set; otherwise cleanly skipped.
 *
 * Retrieval is LOCAL for both (Ollama embeddings + cosine, Days 21–23) and uses the BASELINE pipeline
 * (`improved = false`) so both providers get the IDENTICAL retrieved chunks — the only variable is
 * generation. Per question we measure elapsed generation time (via [MeasuringLlmClient]), tokens,
 * structured-valid ✓/✗, and sources-present ✓/✗; a final SUMMARY reports avg time (speed),
 * structured-valid count (stability), and sources count (grounding) per provider. Answer QUALITY is
 * judged BY EYE — the answers print next to each other; there is no LLM judge.
 *
 * Requires a running Ollama (embeddings + the local chat model) and the index built
 * (`./gradlew :rag:runIndexer`). `ANTHROPIC_API_KEY` is OPTIONAL. Config via [RagConfig.fromEnv]
 * (RAG_INDEX_STRATEGY, RAG_TOP_K, OLLAMA_*) and [org.example.llm.LlmConfig] (OLLAMA_CHAT_MODEL).
 */
fun main() = runBlocking {
    val config = RagConfig.fromEnv()
    val questions = CompareQuestion.load()

    // LOCAL is unconditional (fully offline); CLOUD is optional — a missing key skips it, never crashes.
    val local = LlmClientFactory.build(Provider.LOCAL)
    val cloud = try {
        LlmClientFactory.build(Provider.CLOUD)
    } catch (_: IllegalStateException) {
        null // missing ANTHROPIC_API_KEY → skip cloud, run fully local
    }

    println(
        "Local-vs-cloud RAG comparison — ${questions.size} questions · index: ${config.indexStrategy.fileName} · " +
            "baseline retrieval (top-K ${config.topK}) · timing = generation only",
    )
    if (cloud == null) {
        println("CLOUD skipped — ANTHROPIC_API_KEY not set; running FULLY LOCAL (retrieval + generation).")
    }
    println("=".repeat(100))

    val runs = buildList {
        add(runProvider(Provider.LOCAL.label, local, config, questions))
        if (cloud != null) add(runProvider(Provider.CLOUD.label, cloud, config, questions))
    }

    printSideBySide(questions, runs)
    printSummary(runs)
}

/** One provider's full pass: wrap it in a measuring decorator, answer every question, collect metrics. */
private suspend fun runProvider(
    label: String,
    client: LlmClient,
    config: RagConfig,
    questions: List<CompareQuestion>,
): ProviderRun {
    val measuring = MeasuringLlmClient(client)
    val responder = RagResponder.fromConfig(measuring, config)
    return try {
        ProviderRun(label, questions.map { collectMetric(label, measuring, responder, it) })
    } finally {
        responder.close() // shuts this provider's embedder HTTP client
    }
}

/** One provider's answers for one question set. */
internal data class ProviderRun(val label: String, val metrics: List<QuestionMetric>)

/**
 * Runs one question through the RAG path on one MEASURED provider and captures its metric row. Factored
 * out so the metric-collection path is unit-testable with fakes (a fake clock + a fake retriever), no live
 * Ollama/API. Retrieval is baseline (`improved = false`) so every provider sees the same chunks.
 */
internal suspend fun collectMetric(
    providerLabel: String,
    measuring: MeasuringLlmClient,
    responder: RagResponder,
    q: CompareQuestion,
): QuestionMetric {
    measuring.reset()
    val answer = responder.answer(q.question, useRag = true, improved = false)
    return QuestionMetric(
        provider = providerLabel,
        difficulty = q.difficulty,
        question = q.question,
        answer = answer.answer,
        elapsedMs = measuring.elapsedMs,
        inputTokens = answer.inputTokens,
        outputTokens = answer.outputTokens,
        structuredValid = measuring.structuredValid,
        sourcesPresent = answer.sources.isNotEmpty(),
        dontKnow = answer.dontKnow,
    )
}

/** Prints each question with every provider's answer + metrics stacked beneath it. */
private fun printSideBySide(questions: List<CompareQuestion>, runs: List<ProviderRun>) {
    questions.forEachIndexed { i, q ->
        println("\nQ${i + 1} [${q.difficulty}] ${q.question}")
        println("-".repeat(100))
        runs.forEach { run -> printMetric(run.metrics[i]) }
        println("=".repeat(100))
    }
}

/** One provider's metric block for one question: metrics line, then the answer (for by-eye quality). */
private fun printMetric(m: QuestionMetric) {
    fun mark(ok: Boolean) = if (ok) "✓" else "✗"
    println(
        "\n[${m.provider.uppercase()}] ${m.elapsedMs} ms · tokens ${m.inputTokens} in / ${m.outputTokens} out · " +
            "structured ${mark(m.structuredValid)} · sources ${mark(m.sourcesPresent)}" +
            if (m.dontKnow) " · I DON'T KNOW" else "",
    )
    println(m.answer.lineSequence().joinToString("\n") { "  $it" })
}

/** The SUMMARY: avg time (speed), structured-valid count (stability), sources count (grounding) per provider. */
private fun printSummary(runs: List<ProviderRun>) {
    println("\nSUMMARY")
    runs.forEach { run ->
        val s = summarize(run.label, run.metrics)
        println(
            "  ${s.label.uppercase().padEnd(6)} avg ${s.avgElapsedMs} ms · " +
                "structured-valid ${s.structuredValidCount}/${s.n} · sources ${s.sourcesPresentCount}/${s.n}",
        )
    }
    if (runs.size == 1) {
        println("  (cloud not run — set ANTHROPIC_API_KEY to compare against cloud generation)")
    }
}
