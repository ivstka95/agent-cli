package org.example.agent

import org.example.memory.MemoryStore
import org.example.task.StagePrompts
import org.example.task.TaskHeader
import org.example.task.TaskState
import org.example.task.TaskStateMachine
import org.example.task.TransitionMode

/**
 * Core agent: encapsulates the request -> response cycle.
 *
 * Depends on a [ResponseGenerator] (how the reply + task update are produced)
 * and a [MemoryStore] (the layers composed into the system prompt, and the sink
 * for the auto-extracted task). It does NOT own session history — the REPL holds
 * short-term memory and passes it in on every call.
 */
class Agent(
    private val responseGenerator: ResponseGenerator,
    private val memory: MemoryStore,
) {

    /**
     * Run one user turn over the active task (Day 13).
     *
     * In [TransitionMode.AUTO] this is an autonomous chain: when a stage genuinely completes
     * (CODE verifies a non-empty artifact) it advances and immediately works the next stage,
     * until a stop condition (DONE reached, the model needs input, or a stage stalls).
     *
     * In [TransitionMode.CONFIRM] (the default) it processes exactly ONE stage: when that stage
     * is complete and ready it does NOT advance — it reports a [ChainStep.pendingTransition] and
     * stops, so the REPL can wait for the user's `:next`. All validation (artifact readiness,
     * self-correction, retry-robustness) is identical in both modes; only whether a ready
     * transition is auto-performed differs.
     *
     * [onStep] is invoked as each step finishes so the REPL can print progress in real time.
     */
    suspend fun run(
        userInput: String,
        history: List<Message>,
        mode: TransitionMode = TransitionMode.DEFAULT,
        onStep: (ChainStep) -> Unit = {},
    ): AgentResponse {
        val fullMessages = history + Message(Role.USER, userInput)
        val steps = mutableListOf<ChainStep>()
        var inputTokens = 0
        var outputTokens = 0
        var taskUpdated = false

        fun emit(step: ChainStep) {
            steps += step
            onStep(step)
        }

        // No active task → no stage, no chain. Plain reply (as Day 11).
        if (memory.working.activeTaskContent() == null) {
            val plain = responseGenerator.generate(buildSystemPrompt(null), fullMessages, null)
            inputTokens += plain.inputTokens
            outputTokens += plain.outputTokens
            emit(ChainStep(stage = null, reply = plain.reply))
            return AgentResponse(steps, inputTokens, outputTokens, taskUpdated)
        }

        // Autonomous stage chain. Terminates by construction: the only path that
        // continues advances to a strictly later stage (forward table), bounded by DONE.
        while (true) {
            // The stage is CODE-owned: read it from the file BEFORE this step's update
            // (the model is instructed not to touch the stage field).
            val contentBefore = memory.working.activeTaskContent()!!
            val stage = TaskHeader.parse(contentBefore).stage
            val next = TaskStateMachine.nextStage(stage)

            val gen = responseGenerator.generate(buildSystemPrompt(contentBefore), fullMessages, contentBefore)
            if (applyTaskUpdate(gen.taskUpdate, contentBefore)) taskUpdated = true
            inputTokens += gen.inputTokens
            outputTokens += gen.outputTokens
            var content = memory.working.activeTaskContent()!!
            var stageComplete = gen.stageComplete
            // [Day 15] The model's proposed direction this step (forward/backward), or null.
            var proposed = gen.proposedTransition
            var refinement: Refinement? = null

            // [Day 13 / 3b] One-shot self-correction: the model marked the stage complete but
            // Level 1 fails (the stage's artifact section is empty) and a next stage exists.
            // EXACTLY ONE follow-up for THIS stage — no nested loop. Each stage gets a fresh budget.
            if (stageComplete && next != null && !TaskStateMachine.isArtifactReady(stage, content)) {
                val section = TaskStateMachine.firstEmptyArtifactSection(stage, content)!!
                val followup = responseGenerator.generate(
                    buildSystemPrompt(content) + selfCorrectionNote(section),
                    fullMessages,
                    content,
                )
                if (applyTaskUpdate(followup.taskUpdate, content)) taskUpdated = true
                inputTokens += followup.inputTokens
                outputTokens += followup.outputTokens
                content = memory.working.activeTaskContent()!!
                stageComplete = followup.stageComplete
                proposed = followup.proposedTransition
                refinement = Refinement(stage, followup.reply)
            }

            // [Day 15] Two-layer control: the model PROPOSES a direction, CODE re-validates it
            // against the SAME table. When the model proposes nothing, fall back to the forward
            // successor (Day 13 behavior). An illegal proposal is rejected here (canTransition).
            val target = proposed ?: next
            val canAdvance = stageComplete &&
                target != null &&
                TaskStateMachine.canTransition(stage, target) &&
                TaskStateMachine.isArtifactReady(stage, content)

            if (canAdvance) {
                // canAdvance guarantees a non-null, legal, ready edge.
                val dest = target!!
                if (mode == TransitionMode.CONFIRM) {
                    // [3c] Complete + ready, but DEFER: persist completion AND the validated
                    // direction (so a backward `:next` survives a restart) and stop — the user
                    // accepts with `:next`.
                    memory.working.setStageComplete("true")
                    memory.working.setProposedTransition(dest.stageValue)
                    emit(ChainStep(stage, gen.reply, refinement, pendingTransition = StageTransition(stage, dest)))
                    break
                }
                // [AUTO] Advance now (setActiveStage resets stage_complete and clears the pending
                // proposal for the new stage).
                memory.working.setActiveStage(dest.stageValue)
                emit(ChainStep(stage, gen.reply, refinement, StageTransition(stage, dest)))
                if (dest == TaskState.DONE) break // reached DONE → task finished
                // A backward move (ordinal decreases) is a rework boundary: STOP so the chain can't
                // ping-pong — only forward moves continue the loop (guarantees termination).
                if (dest.ordinal <= stage.ordinal) break
                // else CONTINUE: immediately work the new (later) stage.
            } else {
                // STOP: the model needs input (not complete), or the stage stalled (complete but
                // artifact still not ready), or its proposal was rejected. Persist the model's
                // completion judgment; clear any stale pending proposal — but ONLY if one was set,
                // so a plain chat turn (no proposal) leaves the task file untouched.
                memory.working.setStageComplete(stageComplete.toString())
                if (TaskHeader.parse(content).proposedTransition != null) memory.working.setProposedTransition("")
                emit(ChainStep(stage, gen.reply, refinement))
                break
            }
        }

        return AgentResponse(steps, inputTokens, outputTokens, taskUpdated)
    }

    /**
     * Apply the model's task update, PRESERVING the CODE-owned header fields (`stage`,
     * `stage_complete`) so the model's markdown can never set or regress them. Returns
     * whether it applied.
     */
    private fun applyTaskUpdate(update: String?, activeTask: String?): Boolean {
        if (update != null && activeTask != null) {
            memory.working.overwriteActivePreservingHeader(update)
            return true
        }
        return false
    }

    /** Targeted self-correction feedback appended (once) to the follow-up system prompt. */
    private fun selfCorrectionNote(section: String): String =
        "\n# Self-correction\n" +
            "You marked the stage complete, but the $section section is empty. " +
            "Fill it with concrete content before the stage can advance.\n"

    // ──────────────────────────────────────────────────────────────────────────
    // SINGLE SYSTEM-PROMPT ASSEMBLY POINT
    //
    // The one place where the final system prompt is built. Days 11–15 compose it
    // here from layers, in this priority order:
    //   [Day 14] invariants (must never be violated)        — below (first/highest)
    //   [Day 12+11] long-term memory (active profile + knowledge) — below
    //   [Day 11]    working memory (active task context)     — below
    //   [Day 13]    current stage prompt                     — below (3a)
    // Short-term memory (history) goes into the messages array, not here.
    //
    // Do not scatter system-prompt construction elsewhere — everything plugs in
    // at this function.
    // ──────────────────────────────────────────────────────────────────────────
    private fun buildSystemPrompt(activeTask: String?): String = buildString {
        // [Day 14] Invariants: the FIRST, highest-priority section so they frame
        // everything below. Enforcement is this CODE-owned instruction (the agent
        // checks/refuses via the prompt — there is no runtime checker). Nothing is
        // injected when there are no invariants. They live in the system prompt, so
        // they apply across ALL stages (planning/execution/validation).
        val invariants = memory.invariants.list()
        if (invariants.isNotEmpty()) {
            appendLine("# Invariants (MUST NOT be violated)")
            appendLine(INVARIANTS_INSTRUCTION)
            appendLine()
            invariants.forEach { appendLine("- $it") }
            appendLine()
        }

        // [Day 12] Personalization: the ACTIVE user profile (switchable via the
        // :profile-* commands), injected automatically into every request.
        // [Day 11] plus global knowledge. Days 13–14 (stage prompt, invariants)
        // still plug into this same assembly point.
        appendLine("# User profile")
        appendLine(memory.longTerm.profile().trim())
        appendLine()
        appendLine("# Global knowledge")
        appendLine(memory.longTerm.knowledge().trim())
        appendLine()

        // [Day 11] Working memory: the active task context.
        if (activeTask != null) {
            appendLine("# Active task")
            appendLine(activeTask.trim())
            appendLine()

            // [Day 13 / 3a] Current stage prompt: the stage (parsed from the task
            // file by CODE) selects a behavior fragment, so the same agent acts as a
            // planner / executor / validator per stage. (3b drives auto-transitions
            // from run(), not here; Day 14 prepends invariants above.)
            val stage = TaskHeader.parse(activeTask).stage
            appendLine("# Current stage: ${stage.stageValue}")
            appendLine(StagePrompts.forStage(stage).trim())
            appendLine()

            // [Day 15] Allowed-transitions guidance, derived from the SAME transition table
            // (single source of truth) so the model proposes only legal directions. Empty for
            // terminal stages (DONE) → nothing injected.
            val guidance = StagePrompts.transitionGuidance(stage, TaskStateMachine.allowedTargets(stage))
            if (guidance.isNotEmpty()) {
                appendLine(guidance)
                appendLine()
            }
        }

        append(BASE_INSTRUCTION)
    }

    private companion object {
        const val BASE_INSTRUCTION =
            "You are a helpful CLI assistant. Use the profile, knowledge, and active task above as context for your reply."

        /**
         * [Day 14] CODE-owned invariant enforcement instruction (fixed in code, never user text).
         * Prepended to the invariant lines as the highest-priority section.
         */
        const val INVARIANTS_INSTRUCTION =
            "These are hard, non-negotiable constraints on this work. Before proposing ANY " +
                "solution, decision, or design, check it against every invariant below. If a " +
                "request — or your own proposed solution — would violate an invariant, you MUST " +
                "NOT propose the violating solution. Instead: (a) refuse the violating approach, " +
                "(b) state explicitly which invariant it violates and why, and (c) propose an " +
                "alternative that satisfies ALL invariants. Never work around an invariant by " +
                "substituting a different violation."
    }
}
