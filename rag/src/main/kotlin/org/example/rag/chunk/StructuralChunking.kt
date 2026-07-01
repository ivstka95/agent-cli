package org.example.rag.chunk

import org.example.rag.config.RagConfig
import org.example.rag.model.Chunk
import org.example.rag.model.DocType
import org.example.rag.model.Document

/**
 * Structural chunking: split by document structure instead of a fixed length.
 *
 * - `.md` — split on markdown headers (`^#{1,6} …`); each chunk spans a header to just before the
 *   next header, with `section` = the header text. Content before the first header is a preamble
 *   chunk (`section = null`).
 * - `.kt` — split on top-level (column-0) `fun` / `class` / `object` / `interface` / `enum class`
 *   declarations; `section` = the symbol name. Nested/indented declarations stay inside their
 *   parent's span. The `package`/`import` preamble is a chunk with `section = null`.
 *
 * Line-boundary regex only — no brace counting and no full parser (good enough for logical blocks,
 * per the Day-21 plan).
 *
 * Size cap: a structural section can be arbitrarily large (a long doc section, or a `.kt` file with
 * few top-level declarations), which would overflow the embedder's context limit. Any section longer
 * than [maxChars] is subdivided with [slidingWindows] into fitting sub-chunks — each keeps the same
 * `section` metadata, with continuing ordinals. No truncation: all text is indexed.
 */
class StructuralChunking(
    private val maxChars: Int = RagConfig.DEFAULT_CHUNK_MAX,
    overlap: Int = RagConfig.DEFAULT_CHUNK_OVERLAP,
) : ChunkingStrategy {

    init {
        require(maxChars > 0) { "maxChars must be > 0, was $maxChars" }
    }

    // Overlap for subdividing oversized sections; coerced below maxChars so slidingWindows' require holds.
    private val subOverlap: Int = overlap.coerceIn(0, maxChars - 1)

    override val name: String = NAME

    override fun chunk(document: Document): List<Chunk> {
        if (document.content.isBlank()) return emptyList()
        val lines = document.content.lines()
        val rawCuts = when (document.type) {
            DocType.MD -> markdownCuts(lines)
            DocType.KT -> kotlinCuts(lines)
        }
        return buildChunks(document, lines, rawCuts)
    }

    /** A section boundary: the 0-based line it starts on, and the section label (null = preamble). */
    private data class Cut(val startLine: Int, val section: String?)

    private fun markdownCuts(lines: List<String>): List<Cut> =
        lines.mapIndexedNotNull { idx, line ->
            HEADER_REGEX.find(line)?.let { Cut(idx, it.groupValues[2].trim()) }
        }

    private fun kotlinCuts(lines: List<String>): List<Cut> =
        lines.mapIndexedNotNull { idx, line ->
            // `^` with no leading-whitespace prefix ⇒ only column-0 (top-level) declarations match.
            DECL_REGEX.find(line)?.let { Cut(idx, it.groupValues[2]) }
        }

    private fun buildChunks(document: Document, lines: List<String>, rawCuts: List<Cut>): List<Chunk> {
        // rawCuts are already in ascending line order (produced by mapIndexedNotNull over lines);
        // prepend a preamble cut if the first structural boundary isn't already line 0.
        val cuts = buildList {
            if (rawCuts.isEmpty() || rawCuts.first().startLine > 0) add(Cut(0, null))
            addAll(rawCuts)
        }

        val chunks = mutableListOf<Chunk>()
        var ordinal = 0
        for (i in cuts.indices) {
            val start = cuts[i].startLine
            val end = if (i + 1 < cuts.size) cuts[i + 1].startLine else lines.size
            val text = lines.subList(start, end).joinToString("\n")
            if (text.isBlank()) continue
            val section = cuts[i].section
            if (text.length <= maxChars) {
                chunks += chunkOf(document, NAME, ordinal++, text, section)
            } else {
                // Oversized section: subdivide into fitting windows, all keeping the same section.
                for (window in slidingWindows(text, maxChars, subOverlap)) {
                    chunks += chunkOf(document, NAME, ordinal++, window, section)
                }
            }
        }
        return chunks
    }

    companion object {
        const val NAME = "structural"

        private val HEADER_REGEX = Regex("^(#{1,6})\\s+(.*)$")

        // Optional modifiers/annotations (word/@ tokens), then a top-level declaration keyword and name.
        private val DECL_REGEX =
            Regex("^(?:[\\w@]+\\s+)*(fun|class|object|interface|enum\\s+class)\\s+([A-Za-z0-9_]+)")
    }
}
