package org.example.memory

import java.io.File

/**
 * Working memory (Day 11): the current task's state, stored on files.
 *
 * Multiple tasks may exist, one is "active". The active task is recorded in the
 * `active` pointer file (plain text: the task name), so the active selection
 * survives a restart (pause/resume). Task files use a strict structure with a
 * `stage:` field reserved for Day 13 (default "planning"; transitions are not
 * driven yet).
 *
 * This is the ONLY layer that is auto-extracted: after a successful exchange the
 * agent overwrites the active task file with an LLM-updated version (see the
 * CombinedResponseGenerator). Long-term memory stays manual.
 *
 * Layout (under <root>/working/):
 *   tasks/<name>.md   — one file per task (strict structure)
 *   active            — pointer to the active task name
 */
class WorkingMemory(private val dir: File) {

    private val tasksDir = File(dir, "tasks")
    private val activeFile = File(dir, "active")

    init {
        tasksDir.mkdirs()
    }

    /** Create a new task with the empty strict template and make it active. */
    fun createTask(name: String) {
        val clean = name.trim()
        taskFile(clean).writeText(taskTemplate(clean))
        switchActive(clean)
    }

    /**
     * Set the active task. The task file must already exist.
     * @return true if switched, false if no such task.
     */
    fun switchActive(name: String): Boolean {
        val clean = name.trim()
        if (!taskFile(clean).exists()) return false
        activeFile.writeText(clean)
        return true
    }

    /** The active task name, or null if none is set / the pointer is stale. */
    fun activeTaskName(): String? {
        if (!activeFile.exists()) return null
        val name = activeFile.readText().trim()
        if (name.isEmpty() || !taskFile(name).exists()) return null
        return name
    }

    /** The active task's full markdown, or null if there is no active task. */
    fun activeTaskContent(): String? {
        val name = activeTaskName() ?: return null
        return taskFile(name).readText()
    }

    /**
     * Overwrite the active task file with new content (the auto-extraction sink).
     * No-op if there is no active task.
     */
    fun overwriteActive(content: String) {
        val name = activeTaskName() ?: return
        taskFile(name).writeText(content)
    }

    /**
     * Overwrite the active task with [content] but PRESERVE the CODE-owned header fields
     * (`stage` and `stage_complete`, Day 13). They are driven by the state machine, so a
     * model-supplied task update can never set or regress them. No-op if there is no active
     * task. (A field absent from the current file is left to whatever CODE writes next.)
     *
     * [Day 25] Also preserves the `## Goal` section ONCE it is non-empty: the dialogue goal is task
     * memory, so after it is set it becomes code-owned like the header fields and 10–15 turns of
     * model rewrites cannot drift it. An empty Goal stays model-fillable (planning refines the goal).
     */
    fun overwriteActivePreservingHeader(content: String) {
        val name = activeTaskName() ?: return
        val file = taskFile(name)
        val current = file.readText()
        var result = content
        for (key in CODE_OWNED_HEADER_KEYS) {
            val preserved = headerLineValue(current, key)
            if (preserved != null) result = withHeaderLine(result, key, preserved)
        }
        val currentGoal = sectionBody(current, GOAL_SECTION)
        if (currentGoal.isNotBlank()) result = withSectionBody(result, GOAL_SECTION, currentGoal)
        file.writeText(result)
    }

    /** [Day 25] The active task's `## Goal` body (the dialogue goal / task memory), trimmed; "" if none. */
    fun activeGoal(): String = activeTaskContent()?.let { sectionBody(it, GOAL_SECTION) } ?: ""

    /**
     * [Day 25] Set the active task's `## Goal` body. Once non-empty it is preserved across model
     * rewrites (see [overwriteActivePreservingHeader]). No-op / false if there is no active task.
     */
    fun setActiveGoal(goal: String): Boolean {
        val name = activeTaskName() ?: return false
        val file = taskFile(name)
        file.writeText(withSectionBody(file.readText(), GOAL_SECTION, goal.trim()))
        return true
    }

    /**
     * Set the active task's `stage:` field (Day 13) AND reset `stage_complete: false` — entering
     * a stage means it isn't complete yet, so the two always move together (one write). String-typed
     * on purpose so this layer stays free of the `task` package; the caller validates the value first.
     * @return true if updated, false if there is no active task.
     */
    fun setActiveStage(stageValue: String): Boolean =
        setHeaderFields(
            // Entering a stage also clears any pending proposed transition (Day 15) — a fresh
            // stage has no validated direction waiting yet.
            linkedMapOf("stage" to stageValue.trim(), "stage_complete" to "false", "proposed_transition" to ""),
        )

    /**
     * Set the active task's CODE-owned `stage_complete:` field (Day 13 / 3c). Persisted in
     * the header so stage completion survives a restart (CONFIRM pause/resume).
     * @return true if updated, false if there is no active task.
     */
    fun setStageComplete(value: String): Boolean = setHeaderFields(linkedMapOf("stage_complete" to value.trim()))

    /**
     * Set the active task's CODE-owned `proposed_transition:` field (Day 15) — the pending,
     * code-validated transition target the user can accept with `:next`. Persisted so a
     * backward proposal survives a restart. Pass `""` to clear it. String-typed; the caller
     * passes an already-validated stage value.
     * @return true if updated, false if there is no active task.
     */
    fun setProposedTransition(stageValue: String): Boolean =
        setHeaderFields(linkedMapOf("proposed_transition" to stageValue.trim()))

    /** Apply one or more `<key>: <value>` header lines in a single read+write. */
    private fun setHeaderFields(fields: Map<String, String>): Boolean {
        val name = activeTaskName() ?: return false
        val file = taskFile(name)
        var text = file.readText()
        for ((key, value) in fields) text = withHeaderLine(text, key, value)
        file.writeText(text)
        return true
    }

    /** The value of the `<key>:` header line in [text], or null if there is none. */
    private fun headerLineValue(text: String, key: String): String? =
        text.lines().firstOrNull { it.trimStart().startsWith("$key:") }
            ?.substringAfter("$key:")?.trim()

    /**
     * [text] with its `<key>:` line set to [value] — replacing the existing line, or
     * inserting one right after the `# Task:` header (top if neither exists).
     */
    private fun withHeaderLine(text: String, key: String, value: String): String {
        val lines = text.lines().toMutableList()
        val newLine = "$key: $value"
        val idx = lines.indexOfFirst { it.trimStart().startsWith("$key:") }
        if (idx >= 0) {
            lines[idx] = newLine
        } else {
            val headerIdx = lines.indexOfFirst { it.trimStart().startsWith("# Task:") }
            lines.add(maxOf(headerIdx + 1, 0), newLine)
        }
        return lines.joinToString("\n")
    }

    /**
     * The body-line range of the `## <name>` section in [lines] — the lines AFTER the header up to the
     * next `## ` header (or end of file). null if the section is absent. Shared by the read/write
     * section helpers so the boundary rule lives in one place. For an empty body the range is empty and
     * `range.last + 1` still points at the section end.
     */
    private fun sectionRange(lines: List<String>, header: String): IntRange? {
        val start = lines.indexOfFirst { it.trim() == header }
        if (start < 0) return null
        val end = ((start + 1) until lines.size).firstOrNull { lines[it].trimStart().startsWith("## ") } ?: lines.size
        return (start + 1) until end
    }

    /** The body of the `## <name>` section in [text], trimmed. "" if the section is absent. */
    private fun sectionBody(text: String, header: String): String {
        val lines = text.lines()
        val range = sectionRange(lines, header) ?: return ""
        return lines.subList(range.first, range.last + 1).joinToString("\n").trim()
    }

    /**
     * [text] with the body of its `## <name>` section replaced by [body] (blank-line padded to match
     * the template shape). Returns [text] unchanged if the section is absent.
     */
    private fun withSectionBody(text: String, header: String, body: String): String {
        val lines = text.lines()
        val range = sectionRange(lines, header) ?: return text
        val rebuilt = lines.subList(0, range.first) + listOf("", body, "") + lines.subList(range.last + 1, lines.size)
        return rebuilt.joinToString("\n")
    }

    /** All task names (file stems), sorted. */
    fun listTasks(): List<String> =
        (tasksDir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: emptyArray())
            .map { it.name.removeSuffix(".md") }
            .sorted()

    private fun taskFile(name: String) = File(tasksDir, "$name.md")

    // The bare `-` under each section is the EMPTY-section placeholder: TaskStateMachine's
    // artifact-readiness check treats a section whose only content is `-` as still empty.
    // Keep that in sync if this placeholder ever changes.
    private fun taskTemplate(name: String): String = """
        |# Task: $name
        |stage: planning
        |stage_complete: false
        |step:
        |expected_action:
        |
        |## Goal
        |
        |
        |## Requirements
        |-
        |
        |## Decisions
        |-
        |
        |## Implementation
        |-
        |
        |## Validation
        |-
        |
        |## Done
        |-
        |
        |## TODO
        |-
        |
    """.trimMargin()

    private companion object {
        /** Header fields the state machine owns; preserved on every model overwrite. */
        val CODE_OWNED_HEADER_KEYS = listOf("stage", "stage_complete", "proposed_transition")

        /** [Day 25] The task-memory Goal section; preserved once set (see overwriteActivePreservingHeader). */
        const val GOAL_SECTION = "## Goal"
    }
}
