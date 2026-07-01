package org.example.ragmode

import kotlinx.coroutines.runBlocking
import org.example.llm.AnthropicClient
import org.example.rag.config.RagConfig
import kotlin.system.exitProcess

/**
 * [Day 22] RAG evaluation runner — a SEPARATE run mode from the interactive REPL (`org.example.MainKt`),
 * mirroring how `runDigest` separates the digest daemon. It runs each of the 10 English control questions
 * through BOTH modes side by side:
 *
 *  - WITHOUT RAG (baseline) — bare question → LLM.
 *  - WITH RAG — retrieve top-K chunks from the chosen index → grounded answer + deterministic sources.
 *
 * and prints, per question, both answers plus the expectation and expected sources — the eval set — so
 * the RAG answers' grounding (real repo sources, less hallucination) can be compared to the baseline.
 *
 * Requires `ANTHROPIC_API_KEY`, a running Ollama, and the index built (`./gradlew :rag:runIndexer`).
 * Config via [RagConfig.fromEnv] (RAG_INDEX_STRATEGY, RAG_TOP_K, OLLAMA_*).
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
    println("RAG evaluation — ${questions.size} questions · index: ${config.indexStrategy.fileName} · top-K: ${config.topK}")
    println("=".repeat(100))

    try {
        questions.forEachIndexed { i, q ->
            // RAG first so a missing index fails fast (before any baseline LLM call).
            val rag = responder.answer(q.question, useRag = true)
            val baseline = responder.answer(q.question, useRag = false)

            println("\nQ${i + 1}. ${q.question}")
            println("-".repeat(100))
            println("[WITH RAG]")
            println(indent(rag.answer))
            println("\n[WITHOUT RAG — baseline]")
            println(indent(baseline.answer))
            println("\n[EXPECTATION] ${q.expectation}")
            println("[EXPECTED SOURCES] ${q.expectedSources.joinToString(", ")}")
            println("[RETRIEVED SOURCES] ${if (rag.sources.isEmpty()) "(none)" else rag.sources.joinToString(", ")}")
            println("=".repeat(100))
        }
    } finally {
        responder.close()
    }
}

private fun indent(text: String): String = text.lineSequence().joinToString("\n") { "  $it" }
