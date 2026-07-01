package org.example.rag

import org.example.rag.config.RagConfig
import org.example.rag.retrieve.IndexStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndexStrategyTest {

    @Test
    fun `parse accepts known tokens case- and whitespace-insensitively`() {
        assertEquals(IndexStrategy.STRUCTURAL, IndexStrategy.parse("structural"))
        assertEquals(IndexStrategy.STRUCTURAL, IndexStrategy.parse("  STRUCTURAL "))
        assertEquals(IndexStrategy.FIXED, IndexStrategy.parse("fixed"))
        assertEquals(IndexStrategy.FIXED, IndexStrategy.parse("fixed-size"))
        assertEquals(IndexStrategy.FIXED, IndexStrategy.parse("Fixed-Size"))
    }

    @Test
    fun `parse returns null for unknown tokens`() {
        assertNull(IndexStrategy.parse("dense"))
        assertNull(IndexStrategy.parse(""))
    }

    @Test
    fun `config defaults to structural top-K 5 and resolves index files by strategy`() {
        val config = RagConfig()
        assertEquals(5, config.topK)
        assertEquals(IndexStrategy.STRUCTURAL, config.indexStrategy)
        assertEquals("rag-index/index-structural.json", config.indexFile().path)
        assertEquals("rag-index/index-fixed-size.json", config.indexFile(IndexStrategy.FIXED).path)
    }
}
