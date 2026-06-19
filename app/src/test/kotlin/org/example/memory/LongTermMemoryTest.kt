package org.example.memory

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LongTermMemoryTest {

    private val root: File = createTempDirectory("long-term-mem").toFile()
    private val ltDir = File(root, "long-term")

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `createProfile writes an empty header-only profile and sets it active`() {
        // Given a fresh long-term memory
        val lt = LongTermMemory(ltDir)

        // When a profile is created
        lt.createProfile("concise")

        // Then it becomes active and holds only the header (no preset fields)
        assertEquals("concise", lt.activeProfileName())
        assertEquals("# Profile: concise\n", lt.activeProfileContent())
        assertFalse(lt.activeProfileContent()!!.contains("- "))
    }

    @Test
    fun `switchActiveProfile moves the pointer and rejects an unknown profile`() {
        // Given two profiles
        val lt = LongTermMemory(ltDir)
        lt.createProfile("alpha")
        lt.createProfile("beta")

        // When switching back to an existing profile
        assertTrue(lt.switchActiveProfile("alpha"))
        assertEquals("alpha", lt.activeProfileName())

        // When switching to a non-existent profile
        assertFalse(lt.switchActiveProfile("ghost"))
        // Then the active pointer is unchanged
        assertEquals("alpha", lt.activeProfileName())
    }

    @Test
    fun `active profile and content persist across a fresh instance (restart)`() {
        // Given a profile created, made active, and filled by one instance
        LongTermMemory(ltDir).apply {
            createProfile("concise")
            setProfileField("style", "concise")
            setProfileField("language", "English")
        }

        // When a new instance opens the same directory (simulating a restart)
        val reopened = LongTermMemory(ltDir)

        // Then the active profile and its content are still there
        assertEquals("concise", reopened.activeProfileName())
        val content = reopened.activeProfileContent()!!
        assertTrue(content.contains("- style: concise"))
        assertTrue(content.contains("- language: English"))
    }

    @Test
    fun `listProfiles returns names sorted and includes the auto-created default`() {
        // Given a fresh memory (auto-creates "default") plus two more
        val lt = LongTermMemory(ltDir)
        lt.createProfile("gamma")
        lt.createProfile("alpha")

        // Then listing returns all names sorted
        assertEquals(listOf("alpha", "default", "gamma"), lt.listProfiles())
    }

    @Test
    fun `setProfileField overwrites an existing field without duplicating the line`() {
        // Given an active profile with a style field
        val lt = LongTermMemory(ltDir)
        lt.createProfile("concise")
        lt.setProfileField("style", "concise")

        // When the same field is set again
        lt.setProfileField("style", "detailed")

        // Then there is exactly one style line, with the new value
        val content = lt.activeProfileContent()!!
        assertEquals(1, content.lines().count { it.startsWith("- style:") })
        assertTrue(content.contains("- style: detailed"))
        assertFalse(content.contains("- style: concise"))
    }

    @Test
    fun `setProfileField appends a new field and leaves existing fields intact`() {
        // Given an active profile with a style field
        val lt = LongTermMemory(ltDir)
        lt.createProfile("concise")
        lt.setProfileField("style", "concise")

        // When a different field is set
        lt.setProfileField("language", "English")

        // Then both fields are present
        val content = lt.activeProfileContent()!!
        assertTrue(content.contains("- style: concise"))
        assertTrue(content.contains("- language: English"))
    }

    @Test
    fun `a fresh memory always has a non-empty active profile (default fallback)`() {
        // Given a brand-new long-term memory with no profiles created
        val lt = LongTermMemory(ltDir)

        // Then there is an active profile to inject
        assertEquals("default", lt.activeProfileName())
        assertTrue(lt.profile().isNotBlank())
    }
}
