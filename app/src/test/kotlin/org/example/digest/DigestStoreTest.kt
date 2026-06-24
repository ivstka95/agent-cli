package org.example.digest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Specs for JSON persistence: a save/load round-trip and a clean start when the file is absent. */
class DigestStoreTest {

    @Test
    fun `save then load round-trips the state`() {
        val file = File.createTempFile("digest-state", ".json").apply { delete() }
        try {
            val store = DigestStore(file)
            val state = DigestState(
                seenShas = setOf("a1", "b2"),
                authorCounts = mapOf("Ada" to 2),
                ticks = 3,
            )

            store.save(state)
            val loaded = store.load()

            assertEquals(state, loaded)
            assertEquals(2, loaded.totalTracked) // derived from seenShas.size
            assertTrue(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `load returns an empty state when the file does not exist`() {
        val file = File(File.createTempFile("digest-missing", ".json").apply { delete() }.path)

        assertEquals(DigestState(), DigestStore(file).load())
    }

    @Test
    fun `save creates parent directories`() {
        val dir = File.createTempFile("digest-dir", "").apply { delete() }
        val file = File(dir, "nested/state.json")
        try {
            DigestStore(file).save(DigestState(ticks = 1))

            assertTrue(file.exists())
            assertEquals(1, DigestStore(file).load().ticks)
        } finally {
            dir.deleteRecursively()
        }
    }
}
