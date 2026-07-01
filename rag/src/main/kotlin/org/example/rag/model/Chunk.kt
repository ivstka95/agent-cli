package org.example.rag.model

import kotlinx.serialization.Serializable

/**
 * A unit of text to embed and index, plus its [metadata].
 *
 * [id] is a convenience alias for [ChunkMetadata.chunkId] — a computed accessor (not a stored/persisted
 * field) so there is a single source of truth, letting callers that only hold a [Chunk] read the id
 * without reaching into metadata.
 */
@Serializable
data class Chunk(
    val text: String,
    val metadata: ChunkMetadata,
) {
    val id: String get() = metadata.chunkId
}
