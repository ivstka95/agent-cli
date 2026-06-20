package org.example.task

/**
 * The header fields of a task file (the lines above the first `##` section):
 * `stage`, `step`, `expected_action`. Parsed from the task markdown by CODE so
 * the REPL label and the stage-prompt assembly are reliable (not model-driven).
 *
 * Parsing is GRACEFUL: missing fields fall back to defaults, so old Day 11 task
 * files (which only had `stage:`) and hand-edited files still load.
 */
data class TaskHeader(
    val stage: TaskState,
    val step: String,
    val expectedAction: String,
    /** CODE-owned (Day 13 / 3c): whether the model has marked the CURRENT stage complete. */
    val stageComplete: Boolean,
) {
    companion object {
        /** Parse the header fields from task markdown (null/blank → all defaults). */
        fun parse(markdown: String?): TaskHeader {
            val lines = markdown?.lines() ?: emptyList()
            fun field(key: String): String =
                lines.firstOrNull { it.trimStart().startsWith("$key:") }
                    ?.substringAfter(":")
                    ?.trim()
                    .orEmpty()
            return TaskHeader(
                stage = TaskState.fromStageField(field("stage")),              // missing → PLANNING
                step = field("step"),                                          // missing → ""
                expectedAction = field("expected_action"),                     // missing → ""
                stageComplete = field("stage_complete").equals("true", ignoreCase = true), // missing → false
            )
        }
    }
}
