package org.example.rag.embed

/**
 * Turns text into a dense embedding vector. The seam that keeps the door open to a cloud embedder
 * later — [OllamaEmbedder] is the only impl now; tests use a fake.
 */
interface Embedder {
    suspend fun embed(text: String): FloatArray
}
