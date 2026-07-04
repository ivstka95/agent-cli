package org.example.agent

import org.example.rag.index.SearchResult
import org.example.rag.model.Chunk
import org.example.rag.model.ChunkMetadata
import kotlin.test.Test
import kotlin.test.assertEquals

class RagContextTest {

    private fun hit(file: String, section: String?, text: String): SearchResult =
        SearchResult(
            Chunk(
                text = text,
                metadata = ChunkMetadata(
                    source = "app/src/$file",
                    file = file,
                    section = section,
                    chunkId = "app/src/$file#structural#0",
                    strategy = "structural",
                    ordinal = 0,
                ),
            ),
            0.9f,
        )

    @Test
    fun `contextBlock prefixes each chunk with its source header and blank-line joins`() {
        val block = RagContext.contextBlock(
            listOf(
                hit("Agent.kt", "buildSystemPrompt", "assembles the system prompt"),
                hit("Repl.kt", null, "dispatches commands"),
            ),
        )
        assertEquals(
            "[Source: Agent.kt, section: buildSystemPrompt]\nassembles the system prompt\n\n" +
                "[Source: Repl.kt, section: —]\ndispatches commands",
            block,
        )
    }

    @Test
    fun `sourcesOf de-duplicates by file and section and uses an em-dash for a missing section`() {
        val sources = RagContext.sourcesOf(
            listOf(
                hit("Agent.kt", "buildSystemPrompt", "a"),
                hit("Agent.kt", "buildSystemPrompt", "b"), // same file+section → collapsed
                hit("Repl.kt", null, "c"),
            ),
        )
        assertEquals(listOf("Agent.kt:buildSystemPrompt", "Repl.kt:—"), sources)
    }
}
