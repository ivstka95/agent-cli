package org.example.agent

import org.example.rag.index.SearchResult

/**
 * [Day 25] Shared RAG presentation helpers: turn retrieved [SearchResult] hits into the grounded
 * prompt context block and the deterministic `file:section` source labels.
 *
 * One source of truth used by BOTH the standalone `RagResponder` (Day 22–24) and the Agent's
 * per-turn RAG layer (Day 25), so the `[Source: <file>, section: <section>]` contract the grounding
 * prompt relies on is defined in exactly one place. Pure string formatting from chunk metadata —
 * deterministic, model-independent, and framework-free.
 */
object RagContext {

    /** One context block per hit: `[Source: file, section: …]` then the chunk text; blank-line joined. */
    fun contextBlock(hits: List<SearchResult>): String =
        hits.joinToString("\n\n") { hit ->
            val meta = hit.chunk.metadata
            "[Source: ${meta.file}, section: ${meta.section ?: "—"}]\n${hit.chunk.text}"
        }

    /** A hit's `file:section` label (with an em-dash placeholder for a missing section). */
    fun label(hit: SearchResult): String =
        "${hit.chunk.metadata.file}:${hit.chunk.metadata.section ?: "—"}"

    /** Deterministic `file:section` labels from the hits' metadata, de-duplicated, order preserved. */
    fun sourcesOf(hits: List<SearchResult>): List<String> =
        hits.map { label(it) }.distinct()
}
