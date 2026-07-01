package org.example.rag.index

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.rag.model.Chunk
import java.io.File

/**
 * In-memory [VectorIndex] persisted as JSON, with cosine similarity search.
 *
 * Vectors are stored **normalized** (unit length) — [add] normalizes on the way in — so [search]
 * reduces cosine to a dot product (decision 2 in `rag/PLAN.md`). Small volume (a few hundred chunks),
 * so an in-memory scan is fine; the [VectorIndex] abstraction leaves the SQLite door open.
 */
class JsonVectorIndex(
    private val model: String,
    private val dimension: Int,
    private val strategy: String,
) : VectorIndex {

    private class Entry(val vector: FloatArray, val chunk: Chunk)

    private val entries = mutableListOf<Entry>()

    /** Number of indexed chunks (for stats/tests). */
    val size: Int get() = entries.size

    override fun add(chunk: Chunk, vector: FloatArray) {
        entries += Entry(VectorMath.normalize(vector), chunk)
    }

    override fun search(queryVector: FloatArray, topK: Int): List<SearchResult> {
        if (topK <= 0 || entries.isEmpty()) return emptyList()
        val query = VectorMath.normalize(queryVector)
        return entries
            .map { SearchResult(it.chunk, VectorMath.dot(query, it.vector)) }
            .sortedByDescending { it.score }
            .take(topK)
    }

    override fun save(file: File) {
        file.parentFile?.mkdirs()
        val dto = IndexFile(
            model = model,
            dimension = dimension,
            strategy = strategy,
            normalized = true,
            entries = entries.map { EntryDto(it.vector.toList(), it.chunk) },
        )
        file.writeText(JSON.encodeToString(IndexFile.serializer(), dto))
    }

    // ── On-disk index shape ──────────────────────────────────────────────────────
    @Serializable
    private data class IndexFile(
        val model: String,
        val dimension: Int,
        val strategy: String,
        val normalized: Boolean,
        val entries: List<EntryDto>,
    )

    @Serializable
    private data class EntryDto(val vector: List<Float>, val chunk: Chunk)

    companion object {
        private val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }

        /** Reads an index previously written by [save]. */
        fun load(file: File): JsonVectorIndex {
            val dto = JSON.decodeFromString(IndexFile.serializer(), file.readText())
            val index = JsonVectorIndex(dto.model, dto.dimension, dto.strategy)
            // Stored vectors are already normalized; add() re-normalizing a unit vector is a no-op.
            dto.entries.forEach { index.add(it.chunk, it.vector.toFloatArray()) }
            return index
        }
    }
}
