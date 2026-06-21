package org.example.memory

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvariantStoreTest {

    private val root: File = createTempDirectory("invariants").toFile()
    private val file = File(root, "invariants.md")

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `a missing file means no invariants`() {
        // Given a store over a file that was never created
        val store = InvariantStore(file)

        // Then there are no invariants and the file is created lazily (not yet)
        assertTrue(store.list().isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `add then list round-trips the invariant texts in order`() {
        // Given a fresh store
        val store = InvariantStore(file)

        // When invariants are added
        store.add("No SharedPreferences or EncryptedSharedPreferences")
        store.add("Kotlin-only stack")

        // Then they list back in order, stripped of the bullet prefix
        assertEquals(
            listOf("No SharedPreferences or EncryptedSharedPreferences", "Kotlin-only stack"),
            store.list(),
        )
    }

    @Test
    fun `add dedups an exact existing invariant`() {
        // Given a store with one invariant
        val store = InvariantStore(file)
        store.add("Kotlin-only stack")

        // When the same text is added again
        store.add("Kotlin-only stack")

        // Then it is not duplicated
        assertEquals(listOf("Kotlin-only stack"), store.list())
    }

    @Test
    fun `remove by exact text drops that invariant`() {
        // Given two invariants
        val store = InvariantStore(file)
        store.add("Kotlin-only stack")
        store.add("No third-party auth SDKs")

        // When removing by exact text
        assertTrue(store.remove("Kotlin-only stack"))

        // Then only the other remains
        assertEquals(listOf("No third-party auth SDKs"), store.list())
    }

    @Test
    fun `remove by 1-based index drops that invariant`() {
        // Given three invariants
        val store = InvariantStore(file)
        store.add("A")
        store.add("B")
        store.add("C")

        // When removing the second by index
        assertTrue(store.remove("2"))

        // Then the first and third remain
        assertEquals(listOf("A", "C"), store.list())
    }

    @Test
    fun `remove returns false when nothing matches`() {
        // Given one invariant
        val store = InvariantStore(file)
        store.add("A")

        // When removing a non-existent text and an out-of-range index
        assertFalse(store.remove("ghost"))
        assertFalse(store.remove("9"))

        // Then the invariant is untouched
        assertEquals(listOf("A"), store.list())
    }

    @Test
    fun `clear empties all invariants`() {
        // Given some invariants
        val store = InvariantStore(file)
        store.add("A")
        store.add("B")

        // When cleared
        store.clear()

        // Then none remain
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `invariants persist across a fresh instance (restart)`() {
        // Given invariants written by one instance
        InvariantStore(file).apply {
            add("No SharedPreferences or EncryptedSharedPreferences")
            add("Clean Architecture: domain depends on nothing framework-specific")
        }

        // When a new instance opens the same file (simulating a restart)
        val reopened = InvariantStore(file)

        // Then the invariants are still there
        assertEquals(
            listOf(
                "No SharedPreferences or EncryptedSharedPreferences",
                "Clean Architecture: domain depends on nothing framework-specific",
            ),
            reopened.list(),
        )
    }
}
