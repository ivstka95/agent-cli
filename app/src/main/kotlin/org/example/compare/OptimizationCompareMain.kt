package org.example.compare

import kotlinx.coroutines.runBlocking
import org.example.llm.LlmConfig
import org.example.llm.OllamaLlmClient
import org.example.ragmode.RagResponder
import org.example.rag.config.RagConfig

/**
 * [Day 29] Local-LLM optimization runner — a SEPARATE run mode from the interactive REPL, mirroring
 * `runLocalVsCloud`. It runs the SAME 3 questions through the SAME RAG path on the SAME local model with
 * TWO configs, side by side, so the effect of tuning is visible BEFORE vs AFTER in one run:
 *
 *  - DEFAULT   — no generation options (Ollama's own defaults) + the current [RagResponder.RAG_SYSTEM] prompt.
 *                Byte-identical to how Day 27/28 call the local model.
 *  - OPTIMIZED — tuned options ([OPT_TEMPERATURE] / [OPT_NUM_PREDICT] / [OPT_NUM_CTX]) + the tuned
 *                [RagResponder.RAG_SYSTEM_OPTIMIZED] prompt (concise, code-focused, same grounding contract).
 *
 * Retrieval is identical for both (baseline pipeline, `improved = false`, same top-K chunks) — the only
 * variables are the generation params and the prompt. Per question we print the answer (quality BY EYE),
 * elapsed generation time (via [MeasuringLlmClient]), and tokens; a final SUMMARY reports avg time (speed),
 * **avg output tokens** (conciseness), and structured-valid count (stability) per config.
 *
 * The tuning rationale (for our factual, code-grounded task): a LOW temperature makes answers deterministic
 * and less rambling; an output cap (`num_predict`) plus the concise prompt shorten answers and speed
 * generation up; a LARGER context window (`num_ctx`) fits the whole RAG prompt (grounding contract + tool
 * description + retrieved chunks + question) so retrieved context is not silently truncated. The cap stays
 * generous enough that the forced-tool-use JSON (answer + a few citations) does not truncate — and the
 * responder already retries + falls back if it ever does.
 *
 * Requires a running Ollama (embeddings + the local chat model) and the index built
 * (`./gradlew :rag:runIndexer`). Fully offline — no API key. Base config via [RagConfig.fromEnv] and
 * [LlmConfig.fromEnv] (host + chat model); the two profiles set the generation options explicitly so the
 * before/after is reproducible regardless of ambient `OLLAMA_*` env vars.
 */

/** One config of the local model under comparison: a display [label], its [config], and its [systemPrompt]. */
private data class Profile(val label: String, val config: LlmConfig, val systemPrompt: String)

fun main() = runBlocking {
    val ragConfig = RagConfig.fromEnv()
    val base = LlmConfig.fromEnv()
    val questions = CompareQuestion.load()

    // Both profiles share host + chat model; only the generation options and prompt differ. We clear the
    // tuning fields first so the before/after is reproducible even if OLLAMA_* tuning vars are set: DEFAULT
    // is a true baseline (all null), and `optimized()` then fills the cleared fields with the fixed tuned
    // values (rather than honoring any ambient env override — the demo must be deterministic).
    val cleared = base.copy(temperature = null, maxTokens = null, contextWindow = null)
    val profiles = listOf(
        Profile(
            label = "default",
            config = cleared,
            systemPrompt = RagResponder.RAG_SYSTEM,
        ),
        Profile(
            label = "optimized",
            config = cleared.optimized(),
            systemPrompt = RagResponder.RAG_SYSTEM_OPTIMIZED,
        ),
    )

    println(
        "Local-LLM optimization comparison — ${questions.size} questions · model: ${base.chatModel} · " +
            "index: ${ragConfig.indexStrategy.fileName} · baseline retrieval (top-K ${ragConfig.topK}) · " +
            "timing = generation only",
    )
    println(
        "OPTIMIZED: temperature ${LlmConfig.OPTIMIZED_TEMPERATURE} · num_predict ${LlmConfig.OPTIMIZED_NUM_PREDICT} · " +
            "num_ctx ${LlmConfig.OPTIMIZED_NUM_CTX} · tuned prompt (concise, code-focused)",
    )
    println("=".repeat(100))

    val runs = profiles.map { runProfile(it, ragConfig, questions) }

    printSideBySide(questions, runs)
    printOptimizationSummary(runs)
}

/**
 * One profile's full pass: build its own local client with that profile's options, wrap it for timing, run
 * every question through the RAG path, and collect metrics. Closes both the responder's embedder and the
 * chat client (this runner owns both, unlike the shared-client Day-28 runner).
 */
private suspend fun runProfile(
    profile: Profile,
    ragConfig: RagConfig,
    questions: List<CompareQuestion>,
): ProviderRun {
    val client = OllamaLlmClient(profile.config)
    val measuring = MeasuringLlmClient(client)
    val responder = RagResponder.fromConfig(measuring, ragConfig, systemPrompt = profile.systemPrompt)
    return try {
        ProviderRun(profile.label, questions.map { collectMetric(profile.label, measuring, responder, it) })
    } finally {
        responder.close() // shuts the embedder HTTP client
        client.close() // shuts this profile's chat HTTP client
    }
}

/** The SUMMARY: avg time (speed), avg output tokens (conciseness), structured-valid count (stability) per config. */
private fun printOptimizationSummary(runs: List<ProviderRun>) {
    println("\nSUMMARY (before vs after)")
    runs.forEach { run ->
        val s = summarize(run.label, run.metrics)
        println(
            "  ${s.label.uppercase().padEnd(9)} avg ${s.avgElapsedMs} ms · avg out ${s.avgOutputTokens} tokens · " +
                "structured-valid ${s.structuredValidCount}/${s.n}",
        )
    }
}
