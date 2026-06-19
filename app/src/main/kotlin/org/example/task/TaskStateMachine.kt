package org.example.task

/**
 * Day 13 / 3b: the transition rules and Level-1 (code) validation of the task
 * state machine. Pure logic — no I/O, no model calls. The orchestration (Agent)
 * uses this to decide whether a stage may advance; the MODEL only reports whether
 * the current stage is complete (`stage_complete`), it never picks the next stage.
 *
 * Forward-only for now (PLANNING → EXECUTION → VALIDATION → DONE). Day 15 adds
 * backward edges for rework (e.g. validation → execution); the table generalizes
 * there without touching callers.
 */
object TaskStateMachine {

    /** Legal forward edges: each stage maps to its single successor. */
    private val forward: Map<TaskState, TaskState> = mapOf(
        TaskState.PLANNING to TaskState.EXECUTION,
        TaskState.EXECUTION to TaskState.VALIDATION,
        TaskState.VALIDATION to TaskState.DONE,
    )

    /** The stage that follows [current] in the table, or null if [current] is terminal (DONE). */
    fun nextStage(current: TaskState): TaskState? = forward[current]

    /** Whether [current] → [next] is a legal edge in the transition table. */
    fun canTransition(current: TaskState, next: TaskState): Boolean = forward[current] == next

    /**
     * The artifact section(s) a stage must fill before it may advance. These are
     * the Level-1 (code, no tokens) gate: the model can claim completion, but the
     * stage cannot advance until its required section is actually non-empty.
     */
    private val requiredSections: Map<TaskState, List<String>> = mapOf(
        TaskState.PLANNING to listOf("Requirements", "Decisions"),
        TaskState.EXECUTION to listOf("Implementation"),
        TaskState.VALIDATION to listOf("Validation"),
        TaskState.DONE to emptyList(),
    )

    /** Level 1: are ALL of [stage]'s required artifact sections non-empty in [taskContent]? */
    fun isArtifactReady(stage: TaskState, taskContent: String): Boolean =
        requiredSections.getValue(stage).all { sectionFilled(taskContent, it) }

    /**
     * The name of the first required section of [stage] that is still empty in
     * [taskContent], or null if the stage's artifact is ready. Drives the targeted
     * self-correction feedback ("the <section> section is empty").
     */
    fun firstEmptyArtifactSection(stage: TaskState, taskContent: String): String? =
        requiredSections.getValue(stage).firstOrNull { !sectionFilled(taskContent, it) }

    /**
     * A `## <name>` section counts as filled when its body has at least one line
     * that is neither blank nor the bare `-` template placeholder.
     */
    private fun sectionFilled(taskContent: String, name: String): Boolean =
        sectionBody(taskContent, name).any { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && trimmed != "-"
        }

    /** The body lines of the `## <name>` section: everything up to the next `## ` heading. */
    private fun sectionBody(taskContent: String, name: String): List<String> {
        val lines = taskContent.lines()
        val start = lines.indexOfFirst { it.trim() == "## $name" }
        if (start < 0) return emptyList()
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.trimStart().startsWith("## ") }
        return if (end < 0) rest else rest.take(end)
    }
}
