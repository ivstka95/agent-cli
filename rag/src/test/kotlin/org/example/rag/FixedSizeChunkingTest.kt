package org.example.rag

import org.example.rag.chunk.FixedSizeChunking
import org.example.rag.model.DocType
import org.example.rag.model.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedSizeChunkingTest {

    private val doc = Document(
        path = "docs/sample.md",
        fileName = "sample.md",
        type = DocType.MD,
        // 25 chars, one per letter A..Y — makes boundaries easy to read.
        content = "ABCDEFGHIJKLMNOPQRSTUVWXY",
    )

    @Test
    fun `sliding window produces deterministic boundaries with overlap`() {
        // size=10, overlap=3 ⇒ step=7 ⇒ windows at 0,7,14,21.
        val chunks = FixedSizeChunking(size = 10, overlap = 3).chunk(doc)

        assertEquals(
            listOf("ABCDEFGHIJ", "HIJKLMNOPQ", "OPQRSTUVWX", "VWXY"),
            chunks.map { it.text },
        )
        // Consecutive windows overlap by exactly `overlap` characters.
        assertEquals("HIJ", chunks[0].text.takeLast(3))
        assertEquals("HIJ", chunks[1].text.take(3))
        // Last window is the remainder (shorter than size).
        assertEquals(4, chunks.last().text.length)
    }

    @Test
    fun `ordinals, ids and metadata are set correctly`() {
        val chunks = FixedSizeChunking(size = 10, overlap = 3).chunk(doc)

        assertEquals(listOf(0, 1, 2, 3), chunks.map { it.metadata.ordinal })
        chunks.forEachIndexed { i, c ->
            assertEquals("docs/sample.md", c.metadata.source)
            assertEquals("sample.md", c.metadata.file)
            assertEquals("fixed-size", c.metadata.strategy)
            assertEquals("docs/sample.md#fixed-size#$i", c.metadata.chunkId)
            assertEquals(c.metadata.chunkId, c.id)
            assertNull(c.metadata.section)
        }
    }

    @Test
    fun `blank document yields no chunks`() {
        val blank = doc.copy(content = "   \n  ")
        assertTrue(FixedSizeChunking(size = 10, overlap = 3).chunk(blank).isEmpty())
    }
}
