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
import org.example.rag.retrieve.ThresholdReranker

/**
 * The RAG-mode answer path — the GENERATOR half of RAG (the retriever lives in `:rag`). It is
 * deliberately decoupled from the task-state machine / memory / invariants so RAG comparisons stay
 * clean apples-to-apples:
 *
 *  - `useRag = false` — bare question → LLM (the no-RAG baseline). The retriever is never touched.
 *  - `useRag = true`  — retrieve chunks → assemble a grounded prompt (context + sources +
 *    anti-hallucination instruction) → LLM → deterministically append a `Sources:` line from the
 *    chunks' metadata (reliable, not dependent on the model citing).
 *
 * [Day 23] The retrieval half has two flavors, selected by [improved]:
 *  - baseline (`improved = false`) — the Day-22 pipeline: `search(topK)`, no rewrite, no filter.
 *  - improved (`improved = true`) — LLM query rewrite → `search(retrieveK)` (wide net) → threshold
 *    relevance filter down to `afterK`. The [RagAnswer] carries the before/after retrieved counts.
 *
 * Retrievers are obtained from [retrieverFactory] (keyed by strategy + the improved flag) and cached,
 * so switching `:index`/`:filter` reloads at most once. The factory captures the embedder + index
 * loading and which stages are wired, keeping this class free of file IO and fakeable in tests;
 * [fromConfig] is the production wiring.
 *
 * [close] releases any resources the factory owns (the Ollama HTTP client when built via [fromConfig]).
 */
class RagResponder(
    private val llmClient: LlmClient,
    private val config: RagConfig,
    private val retrieverFactory: (IndexStrategy, Boolean) -> RagRetriever,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    /** The index a `useRag = true` query targets; switched by the REPL's `:index` command. */
    var strategy: IndexStrategy = config.indexStrategy

    /** [Day 23] Whether RAG queries use the improved pipeline (rewrite + filter); toggled by `:filter`. */
    var improved: Boolean = false

    private val retrievers = mutableMapOf<Pair<IndexStrategy, Boolean>, RagRetriever>()

    suspend fun answer(question: String, useRag: Boolean, improved: Boolean = this.improved): RagAnswer {
        if (!useRag) {
            val result = llmClient.complete(BASELINE_SYSTEM, listOf(Message(Role.USER, question)))
            return RagAnswer(result.replyText, emptyList(), result.inputTokens, result.outputTokens)
        }

        // Improved casts a wide net (retrieveK) then filters; baseline searches the narrow topK.
        val searchK = if (improved) config.retrieveK else config.topK
        val retrieval = retriever(improved).retrieve(question, searchK)
        val hits = retrieval.results
        val userMessage = "Context:\n${contextBlock(hits)}\n\nQuestion: $question"
        val result = llmClient.complete(RAG_SYSTEM, listOf(Message(Role.USER, userMessage)))
        val sources = sourcesOf(hits)
        return RagAnswer(
            answer = withSources(result.replyText, sources),
            sources = sources,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
            retrievedBefore = retrieval.retrievedCount,
            scoredSources = scoredSourcesOf(hits),
        )
    }

    private fun retriever(improved: Boolean): RagRetriever =
        retrievers.getOrPut(strategy to improved) { retrieverFactory(strategy, improved) }

    override fun close() = onClose()

    companion object {
        /**
         * Production wiring: an [OllamaEmbedder] shared across strategies and a lazy factory that
         * loads the Day-21 JSON index on first use. The improved retriever fills the `:rag` seats with
         * an [LlmQueryRewriter] (query rewrite) + [ThresholdReranker] (relevance filter); the baseline
         * leaves both as NoOp. [close] shuts the embedder's HTTP client. Used by both entry points
         * (the REPL and the `runRagEval` runner).
         */
        fun fromConfig(llmClient: LlmClient, config: RagConfig): RagResponder {
            val embedder = OllamaEmbedder(config)
            // Load each strategy's index once and share it between the baseline and improved
            // retrievers (the index is read-only, so both flavors can query the same instance).
            val indexes = mutableMapOf<IndexStrategy, JsonVectorIndex>()
            return RagResponder(
                llmClient = llmClient,
                config = config,
                retrieverFactory = { strategy, improved ->
                    val index = indexes.getOrPut(strategy) {
                        val file = config.indexFile(strategy)
                        require(file.exists()) {
                            "RAG index not found: ${file.path}. Build it first with `./gradlew :rag:runIndexer`."
                        }
                        JsonVectorIndex.load(file)
                    }
                    if (improved) {
                        DefaultRagRetriever(
                            embedder = embedder,
                            index = index,
                            queryTransformer = LlmQueryRewriter(llmClient),
                            reranker = ThresholdReranker(config.scoreThreshold, config.afterK),
                        )
                    } else {
                        DefaultRagRetriever(embedder, index) // Day-22 baseline: NoOp rewrite + NoOp rerank
                    }
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

        /** A hit's `file:section` label (with an em-dash placeholder for a missing section). */
        private fun label(hit: SearchResult): String =
            "${hit.chunk.metadata.file}:${hit.chunk.metadata.section ?: "—"}"

        /** Deterministic `file:section` labels from the hits' metadata, de-duplicated, order preserved. */
        internal fun sourcesOf(hits: List<SearchResult>): List<String> =
            hits.map { label(it) }.distinct()

        /** `file:section (0.63)` per kept hit (not de-duplicated) — surfaces cosine scores for the eval. */
        internal fun scoredSourcesOf(hits: List<SearchResult>): List<String> =
            hits.map { "${label(it)} (${formatScore(it.score)})" }

        /** Locale-independent 2-decimal score, so eval output is stable across machines. */
        private fun formatScore(score: Float): String = String.format(java.util.Locale.US, "%.2f", score)

        /** Appends a `Sources: [...]` line built from metadata (no-op when there are no sources). */
        internal fun withSources(reply: String, sources: List<String>): String =
            if (sources.isEmpty()) reply else "$reply\n\nSources: [${sources.joinToString(", ")}]"
    }
}
