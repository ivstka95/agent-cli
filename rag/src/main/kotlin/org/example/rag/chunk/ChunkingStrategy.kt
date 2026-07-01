package org.example.rag.chunk

import org.example.rag.model.Chunk
import org.example.rag.model.Document

/**
 * Splits a [Document] into [Chunk]s. The seam the Day-21 task compares two impls behind
 * ([FixedSizeChunking] vs [StructuralChunking]) and future strategies plug into unchanged.
 */
interface ChunkingStrategy {
    /** Short id used in chunk metadata and the output index file name (`index-<name>.json`). */
    val name: String

    fun chunk(document: Document): List<Chunk>
}
