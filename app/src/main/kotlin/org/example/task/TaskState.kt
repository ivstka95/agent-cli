package org.example.task

/**
 * Day 13: the stage of a task's state machine.
 *
 * The stage is persisted in the task file's `stage:` field (lowercase name) and
 * drives the agent's behavior via a per-stage [StagePrompts] fragment. In 3a the
 * stage only changes manually (`:stage`); auto-transitions arrive in 3b.
 *
 * Forward order is PLANNING -> EXECUTION -> VALIDATION -> DONE; the transition
 * table that enforces that order is 3b/Day 15, not here.
 */
enum class TaskState {
    PLANNING,
    EXECUTION,
    VALIDATION,
    DONE;

    /** Serialized form for the `stage:` field (e.g. "planning"). */
    val stageValue: String get() = name.lowercase()

    companion object {
        /**
         * Strict parse: the canonical stage value, or null if [value] is not a
         * valid stage. Used to validate user input (`:stage <name>`).
         */
        fun parse(value: String?): TaskState? =
            value?.trim()?.lowercase()?.let { v -> entries.firstOrNull { it.stageValue == v } }

        /**
         * Lenient parse for loading task files: an unknown or missing stage field
         * defaults to [PLANNING], so old Day 11 tasks (and hand-edited files) still
         * load without crashing.
         */
        fun fromStageField(value: String?): TaskState = parse(value) ?: PLANNING
    }
}
