package org.example.memory

import java.io.File

/**
 * Invariants (Day 14): GLOBAL hard constraints the agent must never violate
 * (architecture, technical decisions, stack limits, business rules), stored on a
 * file SEPARATELY from the dialogue.
 *
 * Mirrors the knowledge.md storage idiom (Day 11): a flat list of `- <text>`
 * lines, one invariant per line, in a single global file. Applies to ALL tasks
 * (not per-task). String-typed on purpose so this layer stays free of the `task`
 * package, exactly like the rest of `memory/`.
 *
 * Enforcement is NOT here: invariants are injected as the first, highest-priority
 * section of the system prompt (see Agent.buildSystemPrompt), and the agent itself
 * checks/refuses via that CODE-owned instruction. There is no runtime checker.
 *
 * Lazy: the file is created only when the first invariant is added. A missing or
 * empty file simply means "no invariants" (nothing injected, never crashes).
 */
class InvariantStore(private val file: File) {

    /** The invariant texts (the `- ` prefix stripped), in file order. Missing file → empty. */
    fun list(): List<String> {
        if (!file.exists()) return emptyList()
        return file.readText().lines()
            .filter { it.trimStart().startsWith("-") }
            .map { it.trimStart().removePrefix("-").trim() }
            .filter { it.isNotEmpty() }
    }

    /** Append one invariant, deduped by exact (trimmed) text. No-op if it already exists. */
    fun add(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || clean in list()) return
        file.parentFile?.mkdirs()
        val existing = if (file.exists()) file.readText() else ""
        val separator = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
        file.writeText(existing + separator + "- " + clean + "\n")
    }

    /**
     * Remove an invariant by 1-based index (if [textOrIndex] is a valid index) or by exact
     * text match otherwise. Rewrites the file from the survivors.
     * @return true if something was removed, false if nothing matched.
     */
    fun remove(textOrIndex: String): Boolean {
        val current = list()
        val arg = textOrIndex.trim()
        val index = arg.toIntOrNull()?.let { it - 1 }
        val target = if (index != null && index in current.indices) index else current.indexOf(arg)
        if (target < 0) return false
        val survivors = current.toMutableList().apply { removeAt(target) }
        writeAll(survivors)
        return true
    }

    /** Remove all invariants (the file becomes empty). */
    fun clear() = writeAll(emptyList())

    /** Rewrite the file as a flat `- <text>` list (empty content for an empty list). */
    private fun writeAll(invariants: List<String>) {
        if (!file.exists() && invariants.isEmpty()) return
        file.parentFile?.mkdirs()
        file.writeText(invariants.joinToString("") { "- $it\n" })
    }
}
