package org.example.rag.config

import org.example.rag.retrieve.IndexStrategy
import java.io.File

/**
 * Indexing configuration, env-overridable — the `fromEnv()` idiom mirrors :mcp's `ServerBindConfig`.
 *
 * @param ollamaHost base URL of the local Ollama server.
 * @param ollamaModel embedding model name.
 * @param embedDim expected vector dimension; a mismatch only warns (indexing continues).
 * @param repoRoot repo to index (`.` resolves against the `:rag:runIndexer` working dir = project root).
 * @param chunkSize fixed-size chunk length in characters.
 * @param chunkOverlap fixed-size overlap in characters (also the overlap when a structural section
 *   is subdivided).
 * @param chunkMax hard cap on chunk length in characters — a structural section larger than this is
 *   split into fitting sub-chunks so the embedder's context limit is never exceeded.
 * @param indexDir output directory for the JSON indexes (gitignored).
 * @param topK number of chunks a Day-22 retrieval query returns (`VectorIndex.search` top-K).
 * @param indexStrategy which Day-21 index a retrieval query targets by default.
 */
data class RagConfig(
    val ollamaHost: String = DEFAULT_HOST,
    val ollamaModel: String = DEFAULT_MODEL,
    val embedDim: Int = DEFAULT_EMBED_DIM,
    val repoRoot: String = DEFAULT_REPO_ROOT,
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    val chunkOverlap: Int = DEFAULT_CHUNK_OVERLAP,
    val chunkMax: Int = DEFAULT_CHUNK_MAX,
    val indexDir: String = DEFAULT_INDEX_DIR,
    val topK: Int = DEFAULT_TOP_K,
    val indexStrategy: IndexStrategy = DEFAULT_INDEX_STRATEGY,
) {
    /** On-disk index file for [strategy] — `<indexDir>/index-<strategy.fileName>.json`. */
    fun indexFile(strategy: IndexStrategy = indexStrategy): File =
        File(indexDir, "index-${strategy.fileName}.json")

    companion object {
        const val DEFAULT_HOST = "http://localhost:11434"
        const val DEFAULT_MODEL = "nomic-embed-text"
        const val DEFAULT_EMBED_DIM = 768
        const val DEFAULT_REPO_ROOT = "."
        const val DEFAULT_CHUNK_SIZE = 1000
        const val DEFAULT_CHUNK_OVERLAP = 150

        // Conservative cap under nomic-embed-text's ~2048-token context (~4 chars/token ⇒ ~1500 chars).
        const val DEFAULT_CHUNK_MAX = 1500
        const val DEFAULT_INDEX_DIR = "rag-index"
        const val DEFAULT_TOP_K = 5
        val DEFAULT_INDEX_STRATEGY = IndexStrategy.STRUCTURAL

        fun fromEnv(): RagConfig = RagConfig(
            ollamaHost = env("OLLAMA_HOST") ?: DEFAULT_HOST,
            ollamaModel = env("OLLAMA_EMBED_MODEL") ?: DEFAULT_MODEL,
            embedDim = env("RAG_EMBED_DIM")?.toIntOrNull() ?: DEFAULT_EMBED_DIM,
            repoRoot = env("RAG_REPO_ROOT") ?: DEFAULT_REPO_ROOT,
            chunkSize = env("RAG_CHUNK_SIZE")?.toIntOrNull() ?: DEFAULT_CHUNK_SIZE,
            chunkOverlap = env("RAG_CHUNK_OVERLAP")?.toIntOrNull() ?: DEFAULT_CHUNK_OVERLAP,
            chunkMax = env("RAG_CHUNK_MAX")?.toIntOrNull() ?: DEFAULT_CHUNK_MAX,
            indexDir = env("RAG_INDEX_DIR") ?: DEFAULT_INDEX_DIR,
            topK = env("RAG_TOP_K")?.toIntOrNull() ?: DEFAULT_TOP_K,
            indexStrategy = env("RAG_INDEX_STRATEGY")?.let { IndexStrategy.parse(it) } ?: DEFAULT_INDEX_STRATEGY,
        )

        private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    }
}
