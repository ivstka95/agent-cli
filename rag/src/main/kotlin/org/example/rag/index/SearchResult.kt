package org.example.rag.index

import org.example.rag.model.Chunk

/** A ranked hit from [VectorIndex.search]: the matched [chunk] and its similarity [score]. */
data class SearchResult(val chunk: Chunk, val score: Float)
