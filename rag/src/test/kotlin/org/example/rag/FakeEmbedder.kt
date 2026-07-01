package org.example.rag

import org.example.rag.embed.Embedder

/**
 * Deterministic [Embedder] for tests — a small fixed-dimension vector derived from `text.hashCode()`
 * via an LCG. Same text ⇒ same vector (so a query re-embedding a chunk's text ranks that chunk first);
 * different text ⇒ (almost surely) different vector. No network, no live Ollama.
 */
class FakeEmbedder(private val dim: Int = 8) : Embedder {
    override suspend fun embed(text: String): FloatArray {
        var h = text.hashCode().toLong()
        return FloatArray(dim) { i ->
            h = h * 6364136223846793005L + 1442695040888963407L + i
            // Map to a stable, mostly-nonzero float in ~[0, 1).
            (((h ushr 16) and 0xFFFF).toFloat() / 65535f) + 0.001f
        }
    }
}
