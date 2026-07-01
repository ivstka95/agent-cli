package org.example.rag.index

import kotlin.math.sqrt

/** Vector helpers for the index: L2 normalization and cosine similarity. */
object VectorMath {

    /** The L2 (Euclidean) norm of [v]. */
    fun norm(v: FloatArray): Float {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        return sqrt(sum).toFloat()
    }

    /** Returns [v] scaled to unit length. A zero (or empty) vector is returned unchanged. */
    fun normalize(v: FloatArray): FloatArray {
        val n = norm(v)
        if (n == 0f) return v.copyOf()
        return FloatArray(v.size) { v[it] / n }
    }

    /** Dot product. Requires equal lengths. For unit vectors this equals cosine similarity. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "vector size mismatch: ${a.size} vs ${b.size}" }
        var sum = 0.0
        for (i in a.indices) sum += a[i].toDouble() * b[i]
        return sum.toFloat()
    }

    /**
     * Cosine similarity: `dot(a, b) / (‖a‖·‖b‖)`. Robust to non-unit inputs (returns 0 if either
     * vector is zero). For already-normalized vectors prefer [dot] directly.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val denom = norm(a) * norm(b)
        if (denom == 0f) return 0f
        return dot(a, b) / denom
    }
}
