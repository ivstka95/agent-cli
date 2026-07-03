package org.example.ragmode

import org.example.agent.LlmClient
import org.example.rag.config.RagConfig
import org.example.rag.embed.Embedder
import org.example.rag.embed.OllamaEmbedder
import org.example.rag.index.JsonVectorIndex
import org.example.rag.index.VectorIndex
import org.example.rag.retrieve.DefaultRagRetriever
import org.example.rag.retrieve.RagRetriever
import org.example.rag.retrieve.ThresholdReranker

/**
 * [Day 25] Builds the Agent's per-turn RAG retriever: the Day-23 IMPROVED pipeline (LLM query rewrite
 * → wide-net `search(retrieveK)` → similarity-threshold filter down to `afterK`), reused from `:rag`
 * as-is. The Agent owns the LLM; `:rag` stays retrieval-only.
 *
 * The index is loaded LAZILY on the first grounded turn, so a missing index only errors then (with the
 * build-it hint), and plain `:ground off` chat needs neither Ollama nor an index. The Agent's search
 * width is [RagConfig.retrieveK] (the wide net the threshold filter then trims).
 *
 * Returns the retriever plus an [AutoCloseable] for the embedder's HTTP client — the caller (Main /
 * the eval) closes it. This mirrors [RagResponder.fromConfig]; the two paths keep separate embedder +
 * index instances, which is acceptable for a CLI.
 */
fun agentRagRetriever(llmClient: LlmClient, config: RagConfig): Pair<RagRetriever, AutoCloseable> {
    val embedder = OllamaEmbedder(config)
    val delegate = lazy {
        val file = config.indexFile(config.indexStrategy)
        require(file.exists()) {
            "RAG index not found: ${file.path}. Build it first with `./gradlew :rag:runIndexer`."
        }
        improvedRetriever(embedder, JsonVectorIndex.load(file), llmClient, config)
    }
    val retriever = RagRetriever { question, topK -> delegate.value.retrieve(question, topK) }
    return retriever to AutoCloseable { embedder.close() }
}

/**
 * [Day 25] The Day-23 IMPROVED retriever pipeline: LLM query rewrite → wide-net search → similarity-
 * threshold filter (`scoreThreshold` → `afterK`). Assembled in ONE place and shared by both the
 * Agent's per-turn retriever ([agentRagRetriever]) and the standalone [RagResponder.fromConfig]
 * improved arm, so the pipeline can't drift between the two RAG paths.
 */
internal fun improvedRetriever(
    embedder: Embedder,
    index: VectorIndex,
    llmClient: LlmClient,
    config: RagConfig,
): DefaultRagRetriever =
    DefaultRagRetriever(
        embedder = embedder,
        index = index,
        queryTransformer = LlmQueryRewriter(llmClient),
        reranker = ThresholdReranker(config.scoreThreshold, config.afterK),
    )
