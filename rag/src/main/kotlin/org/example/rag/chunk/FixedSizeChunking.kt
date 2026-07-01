package org.example.rag.chunk

import org.example.rag.model.Chunk
import org.example.rag.model.Document

/**
 * Fixed-size chunking: a sliding character window of [size] with [overlap] carried between windows
 * (overlap avoids losing context at chunk boundaries — the course's recommendation).
 *
 * Deterministic: windows start at 0, `step`, `2*step`, … where `step = size - overlap`; the last
 * window is the remainder (shorter than [size]). Blank/whitespace-only windows are skipped.
 * `section` metadata is always null — fixed-size ignores document structure.
 *
 * Char-based (not token-based): no tokenizer dependency; swapping in a token counter later stays
 * internal to this class.
 */
class FixedSizeChunking(
    private val size: Int,
    private val overlap: Int,
) : ChunkingStrategy {

    override val name: String = NAME

    // size/overlap bounds are validated by slidingWindows (single source of truth for that invariant).
    override fun chunk(document: Document): List<Chunk> =
        slidingWindows(document.content, size, overlap)
            .mapIndexed { ordinal, window -> chunkOf(document, NAME, ordinal, window, section = null) }

    companion object {
        const val NAME = "fixed-size"
    }
}
