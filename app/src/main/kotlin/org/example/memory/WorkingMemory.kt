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

    /** All task names (file stems), sorted. */
    fun listTasks(): List<String> =
        (tasksDir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: emptyArray())
            .map { it.name.removeSuffix(".md") }
            .sorted()

    private fun taskFile(name: String) = File(tasksDir, "$name.md")

    private fun taskTemplate(name: String): String = """
        |# Task: $name
        |stage: planning
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
        |## Done
        |-
        |
        |## TODO
        |-
        |
    """.trimMargin()
}
