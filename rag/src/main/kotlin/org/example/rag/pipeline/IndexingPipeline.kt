package org.example.rag.pipeline

import org.example.rag.chunk.ChunkingStrategy
import org.example.rag.embed.Embedder
import org.example.rag.index.JsonVectorIndex
import org.example.rag.model.Chunk
import org.example.rag.model.Document
import java.io.File

/**
 * Ties the indexing pipeline together: for each [ChunkingStrategy], chunk the pre-loaded [documents]
 * → embed → add (the index normalizes) → save `index-<strategy>.json`, returning per-strategy
 * [StrategyStats] for the comparison.
 *
 * Both strategies' indexes are written (the comparison is a task requirement; Day 22 picks one).
 */
class IndexingPipeline(
    private val documents: List<Document>,
    private val embedder: Embedder,
    private val strategies: List<ChunkingStrategy>,
    private val indexDir: File,
    private val model: String,
    private val dimension: Int,
) {
    suspend fun run(): List<StrategyStats> {
        return strategies.map { strategy ->
            val chunks = documents.flatMap { strategy.chunk(it) }
            val index = JsonVectorIndex(model = model, dimension = dimension, strategy = strategy.name)
            val indexed = mutableListOf<Chunk>()
            for (chunk in chunks) {
                // Safety net: the size cap should prevent context-limit failures, but if an embed
                // still fails, warn with the identifying context (chunkId/section/length) and skip
                // that chunk rather than crashing the whole run.
                try {
                    index.add(chunk, embedder.embed(chunk.text))
                    indexed += chunk
                } catch (e: Exception) {
                    System.err.println(
                        "[RAG] warning: skipped chunk ${chunk.metadata.chunkId} " +
                            "(section=${chunk.metadata.section}, ${chunk.text.length} chars): ${e.message}",
                    )
                }
            }
            index.save(File(indexDir, "index-${strategy.name}.json"))
            StrategyStats.from(strategy.name, indexed)
        }
    }
}
