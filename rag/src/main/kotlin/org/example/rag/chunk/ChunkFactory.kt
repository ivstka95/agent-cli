package org.example.rag.chunk

import org.example.rag.model.Chunk
import org.example.rag.model.ChunkMetadata
import org.example.rag.model.Document

/**
 * Splits [text] into a sequence of character windows of length [size], each starting [size]-[overlap]
 * characters after the previous one (so consecutive windows share [overlap] characters). The last
 * window is the remainder (shorter than [size]); blank/whitespace-only windows are dropped.
 *
 * Shared by [FixedSizeChunking] and by [StructuralChunking] when a section exceeds the size cap, so
 * the window logic lives in exactly one place.
 */
internal fun slidingWindows(text: String, size: Int, overlap: Int): List<String> {
    require(size > 0) { "size must be > 0, was $size" }
    require(overlap in 0 until size) { "overlap must be in 0 until size ($size), was $overlap" }
    if (text.isEmpty()) return emptyList()

    val step = size - overlap
    val windows = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val end = minOf(start + size, text.length)
        val window = text.substring(start, end)
        if (window.isNotBlank()) windows += window
        if (end == text.length) break
        start += step
    }
    return windows
}

/**
 * Builds a [Chunk] with a consistent id/metadata scheme, shared by the chunking strategies so the
 * `chunkId` format (`"$source#$strategy#$ordinal"`) lives in one place.
 */
internal fun chunkOf(
    document: Document,
    strategy: String,
    ordinal: Int,
    text: String,
    section: String?,
): Chunk {
    val chunkId = "${document.path}#$strategy#$ordinal"
    return Chunk(
        text = text,
        metadata = ChunkMetadata(
            source = document.path,
            file = document.fileName,
            section = section,
            chunkId = chunkId,
            strategy = strategy,
            ordinal = ordinal,
        ),
    )
}
