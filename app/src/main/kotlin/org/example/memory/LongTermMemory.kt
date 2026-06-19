package org.example.memory

import java.io.File

/**
 * Long-term memory (Days 11 + 12): switchable user profiles + global knowledge,
 * stored on files.
 *
 * Knowledge stays as Day 11: from the app's perspective it is READ-ONLY (the user
 * edits knowledge.md by hand), with [appendKnowledge] backing the `:remember`
 * command. There is NO auto-extraction into long-term memory — auto-extraction
 * targets working memory only (see [WorkingMemory]).
 *
 * Day 12 adds personalization: multiple named profiles, exactly one active at a
 * time. The active profile (preferences such as style/format/language/stack) is
 * injected into every request via [profile]. The active-profile pointer mirrors
 * the working-memory `active` pointer, so the choice survives a restart.
 *
 * Layout (under <root>/long-term/):
 *   profiles/<name>.md  — Day 12: one file per profile (free-form markdown,
 *                         fields stored as `- <field>: <value>` lines)
 *   active-profile      — pointer (plain text: the active profile name)
 *   knowledge.md        — Day 11: global decisions/habits
 *
 * A `default` profile is created and made active on first use, so [profile]
 * always has something to inject and the user always has a file to edit.
 */
class LongTermMemory(private val dir: File) {

    private val profilesDir = File(dir, "profiles")
    private val activeProfileFile = File(dir, "active-profile")
    private val knowledgeFile = File(dir, "knowledge.md")

    init {
        dir.mkdirs()
        profilesDir.mkdirs()
        if (!knowledgeFile.exists()) knowledgeFile.writeText(KNOWLEDGE_TEMPLATE)

        // Guarantee there is always a profile to inject and point at.
        if (listProfiles().isEmpty()) {
            profileFile(DEFAULT_PROFILE).writeText(profileHeader(DEFAULT_PROFILE))
        }
        if (activeProfileName() == null) {
            val target = if (DEFAULT_PROFILE in listProfiles()) DEFAULT_PROFILE else listProfiles().first()
            activeProfileFile.writeText(target)
        }
    }

    // ── Profile injection (used by the system-prompt assembly) ──────────────────

    /** The ACTIVE profile markdown — injected into every request (Day 12). */
    fun profile(): String = activeOrDefaultFile().readText()

    // ── Profile management (Day 12) ─────────────────────────────────────────────

    /** Name of the active profile, or null if the pointer is missing/dangling. */
    fun activeProfileName(): String? {
        if (!activeProfileFile.exists()) return null
        val name = activeProfileFile.readText().trim()
        if (name.isEmpty() || !profileFile(name).exists()) return null
        return name
    }

    /** Content of the active profile, or null if there is none. */
    fun activeProfileContent(): String? = activeProfileName()?.let { profileFile(it).readText() }

    /** Profile names (sorted). */
    fun listProfiles(): List<String> =
        (profilesDir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: emptyArray())
            .map { it.name.removeSuffix(".md") }
            .sorted()

    /** Create a new EMPTY profile (header only) and make it active. */
    fun createProfile(name: String) {
        val clean = name.trim()
        profileFile(clean).writeText(profileHeader(clean))
        switchActiveProfile(clean)
    }

    /** Set the active profile. Returns false (pointer unchanged) if it doesn't exist. */
    fun switchActiveProfile(name: String): Boolean {
        val clean = name.trim()
        if (!profileFile(clean).exists()) return false
        activeProfileFile.writeText(clean)
        return true
    }

    /**
     * Set a preference FIELD on the active profile, overwriting if present.
     * Stored as a single `- <field>: <value>` line — replaces the existing line
     * for that field, or appends one if absent, so a field never duplicates.
     */
    fun setProfileField(field: String, value: String) {
        val file = activeOrDefaultFile()
        val cleanField = field.trim()
        val newLine = "- $cleanField: ${value.trim()}"
        val lines = file.readText().trimEnd('\n').lines().toMutableList()
        val idx = lines.indexOfFirst { it.trimStart().startsWith("- $cleanField:") }
        if (idx >= 0) lines[idx] = newLine else lines.add(newLine)
        file.writeText(lines.joinToString("\n") + "\n")
    }

    // ── Knowledge (Day 11, unchanged) ───────────────────────────────────────────

    /** The global knowledge markdown (manually edited + `:remember`). */
    fun knowledge(): String = knowledgeFile.readText()

    /** Append one line to knowledge.md. Backs the `:remember <text>` command. */
    fun appendKnowledge(line: String) {
        val existing = knowledgeFile.readText()
        val separator = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
        knowledgeFile.writeText(existing + separator + "- " + line.trim() + "\n")
    }

    /** The active profile's file, falling back to the default (init guarantees it exists). */
    private fun activeOrDefaultFile(): File = profileFile(activeProfileName() ?: DEFAULT_PROFILE)

    private fun profileFile(name: String) = File(profilesDir, "$name.md")

    private fun profileHeader(name: String) = "# Profile: $name\n"

    private companion object {
        const val DEFAULT_PROFILE = "default"
        const val KNOWLEDGE_TEMPLATE = "# Knowledge\n\n(Global decisions and habits. Add lines manually or via :remember.)\n"
    }
}
