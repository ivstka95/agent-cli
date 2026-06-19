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
     * Overwrite the active task with [content] but PRESERVE the currently persisted
     * `stage:` line (Day 13). The stage is CODE-owned (the state machine drives it), so
     * a model-supplied task update can never regress it. No-op if there is no active task.
     */
    fun overwriteActivePreservingStage(content: String) {
        val name = activeTaskName() ?: return
        val file = taskFile(name)
        val preserved = stageLineValue(file.readText())
        file.writeText(if (preserved != null) withStageLine(content, preserved) else content)
    }

    /**
     * Set the active task's `stage:` field (Day 13). String-typed on purpose so
     * this layer stays free of the `task` package — the caller (REPL) validates the
     * value against the stage enum first.
     * @return true if updated, false if there is no active task.
     */
    fun setActiveStage(stageValue: String): Boolean {
        val name = activeTaskName() ?: return false
        val file = taskFile(name)
        file.writeText(withStageLine(file.readText(), stageValue.trim()))
        return true
    }

    /** The value of the `stage:` header line in [text], or null if there is none. */
    private fun stageLineValue(text: String): String? =
        text.lines().firstOrNull { it.trimStart().startsWith("stage:") }
            ?.substringAfter("stage:")?.trim()

    /**
     * [text] with its `stage:` line set to [stageValue] — replacing the existing line,
     * or inserting one right after the `# Task:` header (top if neither exists).
     */
    private fun withStageLine(text: String, stageValue: String): String {
        val lines = text.lines().toMutableList()
        val newLine = "stage: $stageValue"
        val idx = lines.indexOfFirst { it.trimStart().startsWith("stage:") }
        if (idx >= 0) {
            lines[idx] = newLine
        } else {
            val headerIdx = lines.indexOfFirst { it.trimStart().startsWith("# Task:") }
            lines.add(maxOf(headerIdx + 1, 0), newLine)
        }
        return lines.joinToString("\n")
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
}
