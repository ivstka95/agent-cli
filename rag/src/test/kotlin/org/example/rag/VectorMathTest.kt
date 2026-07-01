package org.example.rag

import org.example.rag.index.VectorMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorMathTest {

    private val eps = 1e-5f

    @Test
    fun `normalize yields a unit-length vector`() {
        val unit = VectorMath.normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, unit[0], eps)
        assertEquals(0.8f, unit[1], eps)
        assertEquals(1.0f, VectorMath.norm(unit), eps)
    }

    @Test
    fun `normalize is safe on a zero vector`() {
        val z = VectorMath.normalize(floatArrayOf(0f, 0f, 0f))
        assertEquals(0f, VectorMath.norm(z), eps)
    }

    @Test
    fun `cosine is 1 for identical, ~0 for orthogonal, and symmetric`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(2f, 4f, 6f) // same direction, different magnitude
        assertEquals(1.0f, VectorMath.cosine(a, b), eps)

        val x = floatArrayOf(1f, 0f)
        val y = floatArrayOf(0f, 1f)
        assertEquals(0.0f, VectorMath.cosine(x, y), eps)

        assertTrue(VectorMath.cosine(a, x.copyOf(3)) == VectorMath.cosine(x.copyOf(3), a))
    }
}
