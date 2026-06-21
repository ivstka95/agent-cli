package org.example.task

/**
 * Day 13 / 3b: the transition rules and Level-1 (code) validation of the task
 * state machine. Pure logic — no I/O, no model calls. The orchestration (Agent)
 * uses this to decide whether a stage may advance; the MODEL only reports whether
 * the current stage is complete (`stage_complete`), it never picks the next stage.
 *
 * The table is the SINGLE SOURCE OF TRUTH for what's allowed. Forward edges
 * (PLANNING → EXECUTION → VALIDATION → DONE) advance the work; Day 15 adds
 * BACKWARD rework edges (e.g. validation → execution) so a problem can return to
 * the stage that needs fixing. "Can't skip a stage" is enforced purely by the
 * ABSENCE of an edge (there is no planning → done edge).
 */
object TaskStateMachine {

    /** Legal forward edges: each stage maps to its single successor. */
    private val forward: Map<TaskState, TaskState> = mapOf(
        TaskState.PLANNING to TaskState.EXECUTION,
        TaskState.EXECUTION to TaskState.VALIDATION,
        TaskState.VALIDATION to TaskState.DONE,
    )

    /**
     * [Day 15] Legal BACKWARD edges for rework: a stage that found problems can
     * return to an earlier stage. Kept separate from [forward] so [nextStage]
     * stays the single forward successor (the auto-transition / fallback target).
     */
    private val backward: Map<TaskState, Set<TaskState>> = mapOf(
        TaskState.VALIDATION to setOf(TaskState.EXECUTION, TaskState.PLANNING),
        TaskState.EXECUTION to setOf(TaskState.PLANNING),
    )

    /** The stage that follows [current] in the table, or null if [current] is terminal (DONE). */
    fun nextStage(current: TaskState): TaskState? = forward[current]

    /**
     * [Day 15] The stages [current] may legally transition TO — its forward
     * successor first, then any backward rework targets. Drives the allowed-
     * transitions text in the stage prompt and the blocked-transition message,
     * both derived from this same table (single source of truth).
     */
    fun allowedTargets(current: TaskState): List<TaskState> =
        listOfNotNull(forward[current]) + backward[current].orEmpty()

    /** Whether [current] → [next] is a legal edge in the transition table (forward or backward). */
    fun canTransition(current: TaskState, next: TaskState): Boolean = next in allowedTargets(current)

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
