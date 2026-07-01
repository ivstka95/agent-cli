package org.example.rag

import org.example.rag.chunk.StructuralChunking
import org.example.rag.model.DocType
import org.example.rag.model.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuralChunkingTest {

    private val strategy = StructuralChunking()

    @Test
    fun `markdown splits on headers with section = header text`() {
        val doc = Document(
            path = "docs/guide.md",
            fileName = "guide.md",
            type = DocType.MD,
            content = """
                # Title
                intro line

                ## Section A
                aaa

                ## Section B
                bbb
            """.trimIndent(),
        )

        val chunks = strategy.chunk(doc)

        // File starts with a header ⇒ no null preamble; one chunk per header.
        assertEquals(listOf("Title", "Section A", "Section B"), chunks.map { it.metadata.section })
        assertTrue(chunks[0].text.contains("intro line"))
        assertEquals(listOf(0, 1, 2), chunks.map { it.metadata.ordinal })
    }

    @Test
    fun `kotlin splits on top-level declarations, nested ones stay inside their parent`() {
        val doc = Document(
            path = "app/Demo.kt",
            fileName = "Demo.kt",
            type = DocType.KT,
            content = """
                package org.example.demo

                import kotlin.math.sqrt

                fun foo() {
                    val x = 1
                }

                class Bar {
                    fun inner() {}
                }

                object Baz {
                    val y = 2
                }
            """.trimIndent(),
        )

        val chunks = strategy.chunk(doc)
        val sections = chunks.map { it.metadata.section }

        // A null preamble (package/imports) then one chunk per top-level fun/class/object.
        assertEquals(listOf(null, "foo", "Bar", "Baz"), sections)
        // The indented `fun inner()` is NOT a boundary — it lives inside Bar's chunk.
        assertTrue("inner" !in sections)
        val bar = chunks.first { it.metadata.section == "Bar" }
        assertTrue(bar.text.contains("fun inner()"))
        assertTrue(chunks[0].text.contains("package org.example.demo"))
    }

    @Test
    fun `oversized section is subdivided into fitting sub-chunks keeping the same section`() {
        // "# Big" (5) + "\n" + 250 chars = 256 chars > maxChars(100) ⇒ split into windows of ≤100.
        val doc = Document(
            path = "docs/big.md",
            fileName = "big.md",
            type = DocType.MD,
            content = "# Big\n" + "A".repeat(250) + "\n## Small\ntiny",
        )

        val chunks = StructuralChunking(maxChars = 100, overlap = 0).chunk(doc)

        val bigChunks = chunks.filter { it.metadata.section == "Big" }
        // 256 chars at size=100 overlap=0 ⇒ 100 + 100 + 56.
        assertEquals(3, bigChunks.size)
        assertTrue(bigChunks.all { it.text.length <= 100 }, "every sub-chunk must fit under maxChars")
        // No truncation: the sub-chunks reassemble the full section text.
        assertEquals("# Big\n" + "A".repeat(250), bigChunks.joinToString("") { it.text })

        // The small trailing section stays a single chunk, and ordinals are contiguous across the doc.
        assertEquals(listOf("Big", "Big", "Big", "Small"), chunks.map { it.metadata.section })
        assertEquals(listOf(0, 1, 2, 3), chunks.map { it.metadata.ordinal })
        assertEquals(listOf(0, 1, 2, 3).map { "docs/big.md#structural#$it" }, chunks.map { it.metadata.chunkId })
    }
}
