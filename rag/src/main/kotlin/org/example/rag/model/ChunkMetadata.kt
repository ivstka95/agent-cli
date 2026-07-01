package org.example.rag.model

import kotlinx.serialization.Serializable

/**
 * Metadata stored with every chunk (Day-21 task requirement).
 *
 * @param source repo-relative file path the chunk came from.
 * @param file the bare file name.
 * @param section header text (`.md`) or symbol name (`.kt`) the chunk belongs to; null for fixed-size
 *   chunks and for pre-first-section preambles.
 * @param chunkId stable id, `"$source#$strategy#$ordinal"`.
 * @param strategy the chunking strategy name that produced this chunk (`fixed-size` / `structural`).
 * @param ordinal 0-based position of the chunk within its document (per strategy).
 */
@Serializable
data class ChunkMetadata(
    val source: String,
    val file: String,
    val section: String?,
    val chunkId: String,
    val strategy: String,
    val ordinal: Int,
)
