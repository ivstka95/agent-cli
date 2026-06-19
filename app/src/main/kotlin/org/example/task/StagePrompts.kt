package org.example.task

/**
 * Day 13: the per-stage system-prompt fragment. The single assembly point
 * ([org.example.agent.Agent] buildSystemPrompt) appends [forStage] for the
 * active task's stage, so the SAME agent behaves differently per stage.
 *
 * Each fragment carries two things:
 *   1. the stage BEHAVIOR (how to act, which section to fill);
 *   2. a demanding, content-specific COMPLETION CRITERION. In 3a the criterion is
 *      guidance text only — nothing reads it yet. 3b uses it to judge
 *      `stage_complete`. The criteria are intentionally strict: vague agreement
 *      ("looks good", "agreed") does NOT count as done.
 *
 * Each fragment opens with a distinct "<STAGE> stage" phrase so the assembly is
 * easy to verify.
 */
object StagePrompts {

    fun forStage(stage: TaskState): String = when (stage) {
        TaskState.PLANNING -> PLANNING
        TaskState.EXECUTION -> EXECUTION
        TaskState.VALIDATION -> VALIDATION
        TaskState.DONE -> DONE
    }

    private val PLANNING = """
        You are operating in the PLANNING stage of this task. Behave as a planner:
        elicit and capture CONCRETE, testable requirements, make and record the key
        technical decisions, and surface any open questions that block progress.
        Write what you settle into the task's ## Requirements and ## Decisions
        sections. Do not jump ahead to implementation.

        Completion criterion (demanding): planning is complete ONLY when the
        requirements are concrete and testable, the key decisions are made and
        recorded, and no blocking open questions remain. Vague agreement is not
        completion.
    """.trimIndent()

    private val EXECUTION = """
        You are operating in the EXECUTION stage of this task. Work through the
        implementation/design in detail, building on the agreed requirements and
        decisions. Record the concrete design — components, their responsibilities,
        their interactions, and the key technical details — in the task's
        ## Implementation section.

        Completion criterion (demanding): execution is complete ONLY when the design
        is concrete and unambiguous — components, interactions, and key technical
        details are specified, not hand-waved.
    """.trimIndent()

    private val VALIDATION = """
        You are operating in the VALIDATION stage of this task. Critically review the
        result against the requirements: look for gaps, unmet requirements, problems,
        and risks. Be skeptical; do not rubber-stamp. Record concrete findings in the
        task's ## Validation section.

        Completion criterion (demanding): validation is complete ONLY when the
        solution has been checked against each requirement and concrete issues are
        identified — or their absence is explicitly justified.
    """.trimIndent()

    private val DONE = """
        You are operating in the DONE stage of this task. The task is finished.
        Summarize the completed work concisely and confirm the outcome against the
        original goal. Do not start new work.
    """.trimIndent()
}
