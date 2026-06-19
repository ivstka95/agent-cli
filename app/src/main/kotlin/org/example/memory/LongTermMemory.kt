package org.example.memory

import java.io.File

/**
 * Long-term memory (Day 11): global profile + knowledge, stored on files.
 *
 * From the app's perspective this layer is READ-ONLY: the user edits the files
 * by hand. The one exception is [appendKnowledge], which backs the `:remember`
 * command. There is NO auto-extraction into long-term memory — auto-extraction
 * targets working memory only (see [WorkingMemory]).
 *
 * Layout (under <root>/long-term/):
 *   profile.md     — Day 12 will flesh this out (style/format/language/stack)
 *   knowledge.md   — global decisions/habits
 *
 * Both files are created from empty templates if missing, so the agent always
 * has something to inject and the user always has a file to edit.
 */
class LongTermMemory(private val dir: File) {

    private val profileFile = File(dir, "profile.md")
    private val knowledgeFile = File(dir, "knowledge.md")

    init {
        dir.mkdirs()
        if (!profileFile.exists()) profileFile.writeText(PROFILE_TEMPLATE)
        if (!knowledgeFile.exists()) knowledgeFile.writeText(KNOWLEDGE_TEMPLATE)
    }

    /** The user profile markdown (manually edited; Day 12 expands its use). */
    fun profile(): String = profileFile.readText()

    /** The global knowledge markdown (manually edited + `:remember`). */
    fun knowledge(): String = knowledgeFile.readText()

    /** Append one line to knowledge.md. Backs the `:remember <text>` command. */
    fun appendKnowledge(line: String) {
        val existing = knowledgeFile.readText()
        val separator = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
        knowledgeFile.writeText(existing + separator + "- " + line.trim() + "\n")
    }

    private companion object {
        const val PROFILE_TEMPLATE = "# Profile\n\n(Edit this file by hand to personalize the assistant.)\n"
        const val KNOWLEDGE_TEMPLATE = "# Knowledge\n\n(Global decisions and habits. Add lines manually or via :remember.)\n"
    }
}
