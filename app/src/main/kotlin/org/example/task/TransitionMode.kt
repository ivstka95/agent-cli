package org.example.task

/**
 * Day 13 / 3c: how stage transitions are performed within a user turn.
 *
 * - [CONFIRM] (the default): pause at EVERY stage boundary — when a stage is complete and its
 *   artifact is ready, DO NOT advance automatically; signal the user and wait for `:next`. This
 *   is what gives the Day 13 "pause at any stage / resume" behavior (the stage persists, so a
 *   quit+restart resumes mid-task).
 * - [AUTO]: the autonomous chain — advance automatically through ready stages until a stop
 *   condition (DONE / needs input / stalled).
 *
 * The mode is runtime/session state held by the REPL; it is NOT persisted (each start defaults
 * to CONFIRM). The stage itself still persists.
 */
enum class TransitionMode {
    AUTO,
    CONFIRM;

    companion object {
        /** Session default: pause at each boundary and wait for `:next`. */
        val DEFAULT = CONFIRM

        /** Parse `:mode` input ("auto"/"confirm"), or null if not a valid mode. */
        fun parse(value: String?): TransitionMode? =
            value?.trim()?.lowercase()?.let { v -> entries.firstOrNull { it.name.lowercase() == v } }
    }
}
