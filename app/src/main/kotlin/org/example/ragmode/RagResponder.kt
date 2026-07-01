package org.example.ragmode

import org.example.agent.LlmClient
import org.example.agent.Message
import org.example.agent.Role
import org.example.rag.config.RagConfig
import org.example.rag.embed.OllamaEmbedder
import org.example.rag.index.JsonVectorIndex
import org.example.rag.index.SearchResult
import org.example.rag.retrieve.DefaultRagRetriever
import org.example.rag.retrieve.IndexStrategy
import org.example.rag.retrieve.RagRetriever

/**
 * The Day-22 RAG-mode answer path — the GENERATOR half of RAG (the retriever lives in `:rag`). It is
 * deliberately decoupled from the task-state machine / memory / invariants so that "with RAG" vs
 * "without RAG" is a clean apples-to-apples comparison:
 *
 *  - `useRag = false` — bare question → LLM (the baseline). The retriever is never touched.
 *  - `useRag = true`  — retrieve top-K chunks → assemble a grounded prompt (context + sources +
 *    anti-hallucination instruction) → LLM → deterministically append a `Sources:` line from the
 *    chunks' metadata (reliable, not dependent on the model citing).
 *
 * Retrievers are obtained from [retrieverFactory] (keyed by [IndexStrategy]) and cached, so switching
 * `:index` reloads the other index at most once. The factory captures the embedder + index loading,
 * keeping this class free of file IO and fakeable in tests; [fromConfig] is the production wiring.
 *
 * [close] releases any resources the factory owns (the Ollama HTTP client when built via [fromConfig]).
 */
class RagResponder(
    private val llmClient: LlmClient,
    private val config: RagConfig,
    private val retrieverFactory: (IndexStrategy) -> RagRetriever,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    /** The index a `useRag = true` query targets; switched by the REPL's `:index` command. */
    var strategy: IndexStrategy = config.indexStrategy

    private val retrievers = mutableMapOf<IndexStrategy, RagRetriever>()

    suspend fun answer(question: String, useRag: Boolean): RagAnswer {
        if (!useRag) {
            val result = llmClient.complete(BASELINE_SYSTEM, listOf(Message(Role.USER, question)))
            return RagAnswer(result.replyText, emptyList(), result.inputTokens, result.outputTokens)
        }

        val hits = retriever().retrieve(question, config.topK)
        val userMessage = "Context:\n${contextBlock(hits)}\n\nQuestion: $question"
        val result = llmClient.complete(RAG_SYSTEM, listOf(Message(Role.USER, userMessage)))
        val sources = sourcesOf(hits)
        return RagAnswer(withSources(result.replyText, sources), sources, result.inputTokens, result.outputTokens)
    }

    private fun retriever(): RagRetriever = retrievers.getOrPut(strategy) { retrieverFactory(strategy) }

    override fun close() = onClose()

    companion object {
        /**
         * Production wiring: an [OllamaEmbedder] shared across strategies and a lazy per-strategy
         * factory that loads the Day-21 JSON index on first use. [close] shuts the embedder's HTTP
         * client. Used by both entry points (the REPL and the `runRagEval` runner).
         */
        fun fromConfig(llmClient: LlmClient, config: RagConfig): RagResponder {
            val embedder = OllamaEmbedder(config)
            return RagResponder(
                llmClient = llmClient,
                config = config,
                retrieverFactory = { strategy ->
                    val file = config.indexFile(strategy)
                    require(file.exists()) {
                        "RAG index not found: ${file.path}. Build it first with `./gradlew :rag:runIndexer`."
                    }
                    DefaultRagRetriever(embedder, JsonVectorIndex.load(file))
                },
                onClose = embedder::close,
            )
        }

        const val BASELINE_SYSTEM =
            "You are a helpful assistant. Answer the user's question directly and concisely."

        const val RAG_SYSTEM =
            "You answer questions about a codebase using ONLY the provided context. " +
                "Each context block is prefixed with its source as [Source: <file>, section: <section>]. " +
                "Cite the sources you use inline. " +
                "If the context does not contain the answer, say so explicitly — do NOT invent facts or " +
                "rely on outside knowledge."

        /** One context block per hit: `[Source: file, section: …]` then the chunk text; blank-line joined. */
        internal fun contextBlock(hits: List<SearchResult>): String =
            hits.joinToString("\n\n") { hit ->
                val meta = hit.chunk.metadata
                "[Source: ${meta.file}, section: ${meta.section ?: "—"}]\n${hit.chunk.text}"
            }

        /** Deterministic `file:section` labels from the hits' metadata, de-duplicated, order preserved. */
        internal fun sourcesOf(hits: List<SearchResult>): List<String> =
            hits.map { "${it.chunk.metadata.file}:${it.chunk.metadata.section ?: "—"}" }.distinct()

        /** Appends a `Sources: [...]` line built from metadata (no-op when there are no sources). */
        internal fun withSources(reply: String, sources: List<String>): String =
            if (sources.isEmpty()) reply else "$reply\n\nSources: [${sources.joinToString(", ")}]"
    }
}
