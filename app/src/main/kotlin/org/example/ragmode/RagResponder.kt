package org.example.ragmode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.example.agent.LlmClient
import org.example.agent.Message
import org.example.agent.RagContext
import org.example.agent.Role
import org.example.agent.StructuredResult
import org.example.rag.config.RagConfig
import org.example.rag.embed.OllamaEmbedder
import org.example.rag.index.JsonVectorIndex
import org.example.rag.index.SearchResult
import org.example.rag.retrieve.DefaultRagRetriever
import org.example.rag.retrieve.IndexStrategy
import org.example.rag.retrieve.RagRetriever

/**
 * The RAG-mode answer path — the GENERATOR half of RAG (the retriever lives in `:rag`). It is
 * deliberately decoupled from the task-state machine / memory / invariants so RAG comparisons stay
 * clean apples-to-apples:
 *
 *  - `useRag = false` — bare question → LLM (the no-RAG baseline). The retriever is never touched.
 *  - `useRag = true`  — retrieve chunks → assemble a grounded prompt (context + anti-hallucination
 *    instruction) → ONE forced-tool-use [LlmClient.completeStructured] call returning
 *    `{answer, citations, dont_know}` [Day 24]. The `sources` list is still built deterministically from
 *    the chunks' metadata (reliable, not dependent on the model), while `citations` are the model's
 *    VERBATIM fragments (checked against the retrieved chunk text) and `dont_know` is its semantic
 *    backstop when the context doesn't actually answer the question.
 *
 * [Day 24] The structured call mirrors `CombinedResponseGenerator`: first attempt, then ONE retry with a
 * format reminder, then a graceful fallback (raw text as the answer, no citations) — it never crashes.
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

        // [Day 24] Single forced-tool-use call returning {answer, citations, dont_know}. Verbatim
        // fragments and the dont-know flag come from the model; the sources list stays deterministic.
        val messages = listOf(Message(Role.USER, userMessage))
        val structured = structuredAnswer(messages)
        val output = structured.output
        val chunkTexts = hits.map { it.chunk.text }
        return RagAnswer(
            answer = output.answer,
            sources = sourcesOf(hits),
            inputTokens = structured.inputTokens,
            outputTokens = structured.outputTokens,
            retrievedBefore = retrieval.retrievedCount,
            scoredSources = scoredSourcesOf(hits),
            citations = output.citations.map {
                Citation(it.quote, it.source, verbatim = CitationVerifier.isVerbatim(it.quote, chunkTexts))
            },
            dontKnow = output.dontKnow,
        )
    }

    /**
     * [Day 24] Runs the structured RAG call with the same resilience as `CombinedResponseGenerator`:
     * one attempt, one retry with a format reminder if it doesn't parse, then a graceful fallback that
     * treats the raw tool payload as the answer with no citations. Token counts accumulate across tries.
     */
    private suspend fun structuredAnswer(messages: List<Message>): StructuredAnswer {
        val first = completeStructured(RAG_SYSTEM, messages)
        parse(first.toolInputJson)?.let { return StructuredAnswer(it, first.inputTokens, first.outputTokens) }

        val retry = completeStructured(RAG_SYSTEM + RETRY_REMINDER, messages)
        val inputTokens = first.inputTokens + retry.inputTokens
        val outputTokens = first.outputTokens + retry.outputTokens
        parse(retry.toolInputJson)?.let { return StructuredAnswer(it, inputTokens, outputTokens) }

        // Both attempts failed to parse → don't crash: surface the raw payload, cite nothing.
        val fallback = RagStructuredOutput(retry.toolInputJson, emptyList(), dontKnow = false)
        return StructuredAnswer(fallback, inputTokens, outputTokens)
    }

    private suspend fun completeStructured(systemPrompt: String, messages: List<Message>): StructuredResult =
        llmClient.completeStructured(
            systemPrompt = systemPrompt,
            messages = messages,
            toolName = RAG_TOOL_NAME,
            toolDescription = RAG_TOOL_DESCRIPTION,
            inputSchema = RAG_OUTPUT_SCHEMA,
        )

    private fun parse(toolInputJson: String): RagStructuredOutput? =
        runCatching { JSON.decodeFromString<RagStructuredOutput>(toolInputJson) }.getOrNull()

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
                        // [Day 25] Shared improved-pipeline builder (also used by the agent's retriever).
                        improvedRetriever(embedder, index, llmClient, config)
                    } else {
                        DefaultRagRetriever(embedder, index) // Day-22 baseline: NoOp rewrite + NoOp rerank
                    }
                },
                onClose = embedder::close,
            )
        }

        const val BASELINE_SYSTEM =
            "You are a helpful assistant. Answer the user's question directly and concisely."

        // [Day 24] The system prompt states the grounding contract; the field mechanics (verbatim quotes,
        // dont_know) live in the tool description so the model fills them via the forced tool call.
        const val RAG_SYSTEM =
            "You answer questions about a codebase using ONLY the provided context. " +
                "Each context block is prefixed with its source as [Source: <file>, section: <section>]. " +
                "You MUST respond by calling the provided tool. Back every claim with quotes copied " +
                "CHARACTER-FOR-CHARACTER from a SINGLE context block — exact contiguous substrings, never " +
                "altered, and never stitched together from separate spans — each tagged with its source. " +
                "If the context does not actually answer the question, do NOT invent facts or rely on " +
                "outside knowledge — instead say you don't know and ask the user to clarify."

        const val RAG_TOOL_NAME = "answer_with_citations"

        // Appended to the system prompt for the single retry when the first attempt didn't parse.
        const val RETRY_REMINDER =
            "\n\n# Format reminder\nYour previous response was not in the required structured format. " +
                "Respond ONLY by calling the tool — do NOT put JSON or your answer in plain text."

        val RAG_TOOL_DESCRIPTION = """
            Answer the user's question about the codebase using ONLY the provided context blocks. You
            MUST respond by CALLING this tool — never write your answer as plain text. Provide:
            - answer: your natural-language answer, grounded strictly in the context. When you don't
              know (see dont_know), this is instead a short "I don't know" plus a request for the user
              to clarify or rephrase.
            - citations: an array of the exact fragments that back your answer. Each item has:
                - quote: a span copied CHARACTER-FOR-CHARACTER from ONE context block. Do not add, remove,
                  reorder, or change ANY character — including punctuation, parentheses, or function
                  arguments (e.g. do not add or drop an argument like `, turn`). Do NOT stitch non-adjacent
                  fragments into one quote: each quote must be ONE contiguous span exactly as it appears in
                  the source; to cite two separate spans, emit TWO separate citations. Prefer a SHORT span
                  you can copy perfectly over a long one you might get wrong — a short exact quote beats a
                  long inexact one. The quote is verified as an exact substring of the retrieved chunk; if it
                  is not an exact substring it is worthless, so copy precisely.
                - source: that block's source as "file:section" exactly as shown in its
                  [Source: <file>, section: <section>] header.
              For a normal grounded answer, provide at least one citation. When dont_know is true,
              return an empty array.
            - dont_know: a boolean. Set true when the context does NOT actually answer the question
              (even if some blocks were retrieved) — then answer says you don't know and asks the user
              to clarify. Set false for a normal grounded answer. Never invent an answer to avoid
              setting this flag.
        """.trimIndent()

        /** JSON Schema for the {answer, citations, dont_know} tool input (strict). */
        val RAG_OUTPUT_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("answer") {
                    put("type", "string")
                    put("description", "Natural-language answer grounded in the context (or the 'I don't know' ask).")
                }
                putJsonObject("citations") {
                    put("type", "array")
                    put("description", "Verbatim fragments backing the answer, each with its source.")
                    putJsonObject("items") {
                        put("type", "object")
                        put("additionalProperties", false)
                        putJsonObject("properties") {
                            putJsonObject("quote") {
                                put("type", "string")
                                put("description", "A fragment copied VERBATIM from a context block.")
                            }
                            putJsonObject("source") {
                                put("type", "string")
                                put("description", "The block's source as \"file:section\".")
                            }
                        }
                        putJsonArray("required") {
                            add("quote")
                            add("source")
                        }
                    }
                }
                putJsonObject("dont_know") {
                    put("type", "boolean")
                    put("description", "True when the context does not actually answer the question.")
                }
            }
            putJsonArray("required") {
                add("answer")
                add("citations")
                add("dont_know")
            }
        }

        private val JSON = Json { ignoreUnknownKeys = true }

        /** [Day 25] Delegates to the shared [RagContext] so the `[Source: file, section]` contract lives once. */
        internal fun contextBlock(hits: List<SearchResult>): String = RagContext.contextBlock(hits)

        /** [Day 25] Deterministic, de-duplicated `file:section` labels — via the shared [RagContext]. */
        internal fun sourcesOf(hits: List<SearchResult>): List<String> = RagContext.sourcesOf(hits)

        /** `file:section (0.63)` per kept hit (not de-duplicated) — surfaces cosine scores for the eval. */
        internal fun scoredSourcesOf(hits: List<SearchResult>): List<String> =
            hits.map { "${RagContext.label(it)} (${formatScore(it.score)})" }

        /** Locale-independent 2-decimal score, so eval output is stable across machines. */
        private fun formatScore(score: Float): String = String.format(java.util.Locale.US, "%.2f", score)
    }
}

/** [Day 24] The parsed structured payload plus the token usage the caller accumulates across tries. */
private data class StructuredAnswer(
    val output: RagStructuredOutput,
    val inputTokens: Int,
    val outputTokens: Int,
)

/** The `answer_with_citations` tool payload, as returned by the model. */
@Serializable
private data class RagStructuredOutput(
    val answer: String,
    val citations: List<CitationJson> = emptyList(),
    @SerialName("dont_know") val dontKnow: Boolean = false,
)

@Serializable
private data class CitationJson(
    val quote: String,
    val source: String,
)
