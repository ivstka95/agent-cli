package org.example.rag.index

import org.example.rag.model.Chunk
import java.io.File

/**
 * A vector store: add embedded chunks, then rank them by similarity to a query vector.
 *
 * The seam that keeps the door open to SQLite/FAISS later, and — via [search]'s ranked top-K — to
 * Day-22 retrieval and later reranking. [JsonVectorIndex] is the only impl now; loading is a
 * [JsonVectorIndex.load] companion (an instance can't generically reconstruct itself).
 */
interface VectorIndex {
    /** Adds a chunk with its embedding. Implementations store the vector normalized (unit length). */
    fun add(chunk: Chunk, vector: FloatArray)

    /** The [topK] chunks most similar to [queryVector], highest score first. */
    fun search(queryVector: FloatArray, topK: Int): List<SearchResult>

    /** Persists the index to [file] (creating parent directories as needed). */
    fun save(file: File)
}
