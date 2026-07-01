package org.example.rag.pipeline

import org.example.rag.model.Chunk

/**
 * Summary of one chunking strategy's output, for the 2-strategy comparison (Day-21 requirement).
 * Sizes are chunk text lengths in characters.
 */
data class StrategyStats(
    val strategy: String,
    val chunkCount: Int,
    val minSize: Int,
    val maxSize: Int,
    val avgSize: Int,
    val totalChars: Int,
) {
    companion object {
        fun from(strategy: String, chunks: List<Chunk>): StrategyStats {
            val sizes = chunks.map { it.text.length }
            val total = sizes.sum()
            return StrategyStats(
                strategy = strategy,
                chunkCount = chunks.size,
                minSize = sizes.minOrNull() ?: 0,
                maxSize = sizes.maxOrNull() ?: 0,
                avgSize = if (chunks.isEmpty()) 0 else total / chunks.size,
                totalChars = total,
            )
        }
    }
}
