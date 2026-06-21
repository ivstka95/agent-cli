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

    /**
     * [Day 15] The allowed-transitions guidance for [stage], appended to the stage prompt by
     * the single assembly point. The [allowedTargets] list is passed in from the
     * [TaskStateMachine] table (single source of truth) so this never hand-writes the edges.
     *
     * It tells the model: these are the ONLY legal moves (so stages can't be skipped, and
     * natural-language "skip to done" requests must be refused), and how to use the optional
     * `proposed_transition` field — forward ONLY on success, backward on problems, nothing if
     * unfinished. Returns "" for a stage with no outgoing edges (DONE), so nothing is injected.
     */
    fun transitionGuidance(stage: TaskState, allowedTargets: List<TaskState>): String {
        if (allowedTargets.isEmpty()) return ""
        val targets = allowedTargets.joinToString(", ") { it.stageValue }
        return """
            # Stage transitions
            From the current stage (${stage.stageValue}) the ONLY legal moves are: $targets. Stages
            CANNOT be skipped — if the user asks to skip ahead or jump straight to the final result
            (e.g. "just give me the final solution", "skip to done"), REFUSE and explain that each
            stage must be completed in order; you may only move along the transitions listed above.

            Use the optional proposed_transition field to propose ONE of those targets, but ONLY:
            - a FORWARD move if this stage is fully done AND has NO problems/blockers (completed
              successfully);
            - a BACKWARD move (rework) if this stage is done but found problems that belong to an
              earlier stage — for VALIDATION, propose done ONLY if NO blockers were found; if any
              were found, propose execution or planning instead, NOT done;
            - nothing (omit it) if this stage is not finished.

            The direction you propose IS your verdict — there is no separate pass/fail flag. Code
            re-validates every proposal against these same rules and rejects anything illegal.
        """.trimIndent()
    }

    private val PLANNING = """
        You are operating in the PLANNING stage of this task. Each stage produces NEW work
        in its OWN section — never echo a previous stage's content; build on it, do not
        repeat it. Behave as a planner: elicit and capture CONCRETE, testable
        requirements, make and record the key technical decisions, and surface any open
        questions that block progress. Write what you settle into the task's
        ## Requirements and ## Decisions sections. Do not jump ahead to implementation.

        Gather requirements thoroughly in the chat — ask clarifying questions in full, as
        many as needed; do not compress them. The chat reply IS the work here. Only once
        planning is complete (all requirements/decisions settled, no blocking questions),
        give a brief recap of what was settled before moving on.

        Completion criterion (demanding): planning is complete ONLY when the
        requirements are concrete and testable, the key decisions are made and
        recorded, and no blocking open questions remain. Vague agreement is not
        completion.
    """.trimIndent()

    private val EXECUTION = """
        You are operating in the EXECUTION stage of this task. Each stage produces NEW
        work in its OWN section — never echo a previous stage's content; build on it, do
        not repeat it. Do NOT restate or summarize the requirements/decisions — they are
        already captured in their sections. If your reply repeats the plan instead of
        designing components, you are doing it wrong. Your output here MUST be new design
        work written into ## Implementation: named components, their responsibilities,
        their interfaces/APIs, their interactions, and key technical details.

        In your chat reply, give a SHORT summary (a few sentences) of what you designed
        this stage — name the main components and the key design decisions — so the user
        sees the progress. Put the full detailed design in ## Implementation; keep the
        chat reply a concise recap that points to the task for full detail, NOT a copy of
        the section and NOT a one-line "done".

        Completion criterion (demanding): execution is complete ONLY when the design is
        concrete and unambiguous — components, interactions, and key technical details
        are specified, not hand-waved. Do NOT set the stage complete if ## Implementation
        merely restates requirements or decisions; it is complete ONLY when
        ## Implementation contains an actual concrete component design (named components,
        interfaces, interactions, technical specifics) — not a summary of the plan.
    """.trimIndent()

    private val VALIDATION = """
        You are operating in the VALIDATION stage of this task. Each stage produces NEW
        work in its OWN section — never echo a previous stage's content; build on it, do
        not repeat it. Do NOT restate the plan or the design. Your only job is to
        critically review the existing design against each requirement — record NEW
        findings (gaps, unmet requirements, risks, or explicitly justified absence of
        issues) in ## Validation, not a summary of prior stages. Be skeptical; do not
        rubber-stamp.

        In your chat reply, give a SHORT summary (a few sentences) of what you found this
        stage — name the main findings and risks (or confirm none) — so the user sees the
        progress. Put the full detailed review in ## Validation; keep the chat reply a
        concise recap that points to the task for full detail, NOT a copy of the section
        and NOT a one-line "done".

        Completion criterion (demanding): validation is complete ONLY when the solution
        has been checked against each requirement and concrete issues are identified — or
        their absence is explicitly justified. Do NOT set the stage complete if
        ## Validation merely restates the plan or design; it is complete ONLY when
        ## Validation contains actual review findings checked against the requirements.
    """.trimIndent()

    private val DONE = """
        You are operating in the DONE stage of this task. The task is finished.
        Summarize the completed work concisely and confirm the outcome against the
        original goal. Do not start new work.
    """.trimIndent()
}
