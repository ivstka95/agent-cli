package org.example.rag

import kotlinx.coroutines.runBlocking
import org.example.rag.chunk.FixedSizeChunking
import org.example.rag.chunk.StructuralChunking
import org.example.rag.config.RagConfig
import org.example.rag.embed.OllamaEmbedder
import org.example.rag.load.DocumentLoader
import org.example.rag.pipeline.IndexingPipeline
import org.example.rag.pipeline.StrategyStats
import java.io.File

/**
 * Day-21 indexing entry point: load repo docs → chunk with both strategies → embed via Ollama →
 * normalize → write `rag-index/index-<strategy>.json` → print the 2-strategy comparison.
 *
 * Requires a running Ollama (default `http://localhost:11434`, model `nomic-embed-text`).
 */
fun main() = runBlocking {
    val config = RagConfig.fromEnv()
    val repoRoot = File(config.repoRoot)
    val indexDir = File(config.indexDir)

    println("[RAG] indexing ${repoRoot.absoluteFile.normalize()}")
    println("[RAG] embedder: ${config.ollamaModel} @ ${config.ollamaHost} (expecting ${config.embedDim}-dim)")

    val loader = DocumentLoader(repoRoot)
    val documents = loader.load()
    println("[RAG] loaded ${documents.size} documents")

    val embedder = OllamaEmbedder(config)
    val strategies = listOf(
        FixedSizeChunking(config.chunkSize, config.chunkOverlap),
        StructuralChunking(config.chunkMax, config.chunkOverlap),
    )
    val pipeline = IndexingPipeline(
        documents = documents,
        embedder = embedder,
        strategies = strategies,
        indexDir = indexDir,
        model = config.ollamaModel,
        dimension = config.embedDim,
    )

    val stats = try {
        pipeline.run()
    } finally {
        embedder.close()
    }

    stats.forEach { println("[RAG] wrote ${File(indexDir, "index-${it.strategy}.json").path} (${it.chunkCount} chunks)") }
    printComparison(stats)
}

private fun printComparison(stats: List<StrategyStats>) {
    println()
    println("=== Chunking strategy comparison ===")
    println("%-14s %8s %8s %8s %8s %10s".format("strategy", "chunks", "min", "max", "avg", "total"))
    stats.forEach { s ->
        println("%-14s %8d %8d %8d %8d %10d".format(s.strategy, s.chunkCount, s.minSize, s.maxSize, s.avgSize, s.totalChars))
    }
    println()
    println("Note: fixed-size gives uniform, evenly-sized chunks but may split mid-idea at boundaries;")
    println("      structural gives fewer, semantically-aligned chunks of uneven size (by header/symbol).")
}
