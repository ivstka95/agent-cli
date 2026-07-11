package org.example.repl

import org.example.agent.Agent
import org.example.agent.ChainStep
import org.example.agent.Message
import org.example.agent.Role
import org.example.llm.LlmProviderSwitch
import org.example.llm.Provider
import org.example.llm.SwitchResult
import org.example.memory.MemoryStore
import org.example.rag.retrieve.IndexStrategy
import org.example.ragmode.RagResponder
import org.example.task.TaskHeader
import org.example.task.TaskState
import org.example.task.TaskStateMachine
import org.example.task.TransitionMode

/**
 * Interactive read-eval-print loop.
 *
 * Owns no history itself — short-term memory lives in [MemoryStore.shortTerm],
 * which accumulates the session dialog and is passed to the Agent each turn.
 * Also handles the memory commands (working-memory tasks + long-term :remember).
 *
 * [out] is the output sink (defaults to stdout); tests inject a capture. The
 * transition [mode] and the pending-confirmation are session state — not persisted.
 *
 * [Day 22] [ragResponder] is the optional RAG-mode answer path (null when no index/embedder is
 * wired, mirroring the optional agentic loop). When RAG is toggled on, plain questions route to it
 * instead of the agent — a separate, stateless Q&A path (not mixed into short-term memory) so the
 * with/without-RAG comparison stays clean.
 */
class Repl(
    private val agent: Agent,
    private val memory: MemoryStore,
    private val out: (String) -> Unit = ::println,
    private val ragResponder: RagResponder? = null,
    // [Day 27] Optional live provider toggle (`:llm local|cloud`). Null → single fixed provider and no
    // tag (tests / back-compat). Mirrors the optional [ragResponder].
    private val llmSwitch: LlmProviderSwitch? = null,
) {

    /** Day 13 / 3c: session transition mode (default CONFIRM); not persisted. */
    private var mode: TransitionMode = TransitionMode.DEFAULT

    /** [Day 22] Session RAG toggle (default off); not persisted. */
    private var ragEnabled: Boolean = false

    /**
     * [Day 25] Whether the AGENT path grounds each turn with RAG (task memory + retrieval + history —
     * the production-like chat). Default ON; `:ground off` drops to the plain Days 11–15 agent. This is
     * independent of [ragEnabled]: `:rag on` routes to the standalone stateless Q&A (Day 22–24) and
     * takes precedence in submit(); `:ground` only affects the agent path. Not persisted.
     */
    private var groundEnabled: Boolean = true

    suspend fun start() {
        out("CLI Agent. Type a message, :help for commands, or :quit to exit.")
        while (true) {
            // "You: " is the prompt label; the terminal echoes the typed text onto
            // the same line, so the turn reads "You: <message>". We never re-print
            // the input ourselves — that would duplicate it in an interactive terminal.
            print("You: ")
            val input = readlnOrNull()?.trim()

            // EOF (Ctrl-D / piped input ends): break the prompt line, then exit.
            if (input == null) {
                out("")
                break
            }
            if (input.isEmpty()) continue
            if (submit(input)) break // line signalled exit
        }
        out("Bye.")
    }

    /** Process one input line (command or chat). Returns true if the REPL should exit. */
    internal suspend fun submit(input: String): Boolean {
        if (input.startsWith(":")) return handleCommand(input)
        val responder = ragResponder
        if (ragEnabled && responder != null) ragChat(responder, input) else chat(input)
        return false
    }

    /** Handle a `:`-prefixed command. Returns true if the REPL should exit. */
    private suspend fun handleCommand(input: String): Boolean {
        val parts = input.split(" ", limit = 2)
        val command = parts[0]
        val arg = parts.getOrNull(1)?.trim().orEmpty()

        when (command) {
            ":quit", ":q" -> return true
            ":help" -> printHelp()
            ":task-new" -> {
                if (arg.isEmpty()) {
                    out("Usage: :task-new <name>")
                } else {
                    memory.working.createTask(arg)
                    out("Created and switched to task '$arg'.")
                }
            }
            ":task-switch" -> {
                if (arg.isEmpty()) {
                    out("Usage: :task-switch <name>")
                } else if (memory.working.switchActive(arg)) {
                    out("Active task is now '$arg'.")
                } else {
                    out("No such task: '$arg'. Use :task-list to see tasks.")
                }
            }
            ":task-list" -> printList(
                memory.working.listTasks(),
                memory.working.activeTaskName(),
                "No tasks yet. Create one with :task-new <name>.",
            )
            ":task-show" -> {
                val content = memory.working.activeTaskContent()
                if (content == null) {
                    out("No active task. Create one with :task-new <name>.")
                } else {
                    out(content)
                }
            }
            ":stage" -> {
                // [Day 15] Manually set the active task's stage, but ONLY along a legal edge of
                // the transition table (so a manual jump can't skip stages either). Validation
                // lives here (against the enum + the table) so WorkingMemory stays string-typed.
                val target = TaskState.parse(arg)
                val content = memory.working.activeTaskContent()
                when {
                    arg.isEmpty() ->
                        out("Usage: :stage <planning|execution|validation|done>")
                    target == null ->
                        out("Invalid stage: '$arg'. Valid: planning, execution, validation, done.")
                    content == null ->
                        out("No active task. Create one with :task-new <name>.")
                    else -> {
                        val current = TaskHeader.parse(content).stage
                        if (TaskStateMachine.canTransition(current, target)) {
                            // setActiveStage also resets stage_complete (manual stage → not complete yet).
                            memory.working.setActiveStage(target.stageValue)
                            out("Stage set to ${target.stageValue}.")
                        } else {
                            out(blockedTransition(current, target))
                        }
                    }
                }
            }
            ":mode" -> {
                // [3c] Session transition mode. No arg → show it; otherwise set auto/confirm.
                if (arg.isEmpty()) {
                    out("Transition mode: ${mode.name.lowercase()} (default: confirm).")
                } else {
                    val target = TransitionMode.parse(arg)
                    if (target == null) {
                        out("Invalid mode: '$arg'. Valid: auto, confirm.")
                    } else {
                        mode = target
                        out("Transition mode: ${mode.name.lowercase()}.")
                    }
                }
            }
            ":rag" -> {
                // [Day 22] Toggle RAG mode. No arg → show state; on/off → set it. When on, plain
                // questions route to the retriever+LLM path instead of the agent.
                val responder = ragResponder
                when {
                    responder == null -> out("RAG is not available (no index/embedder wired).")
                    arg.isEmpty() -> out("RAG mode: ${if (ragEnabled) "on" else "off"} (index: ${responder.strategy.fileName}).")
                    else -> when (arg.lowercase()) {
                        "on" -> {
                            ragEnabled = true
                            out("RAG mode: on (index: ${responder.strategy.fileName}).")
                        }
                        "off" -> {
                            ragEnabled = false
                            out("RAG mode: off.")
                        }
                        else -> out("Usage: :rag [on|off]")
                    }
                }
            }
            ":index" -> {
                // [Day 22] Which Day-21 index RAG queries target. No arg → show it; otherwise switch.
                val responder = ragResponder
                when {
                    responder == null -> out("RAG is not available (no index/embedder wired).")
                    arg.isEmpty() -> out("Index: ${responder.strategy.fileName}. Valid: structural, fixed.")
                    else -> when (val target = IndexStrategy.parse(arg)) {
                        null -> out("Invalid index: '$arg'. Valid: structural, fixed.")
                        else -> {
                            responder.strategy = target
                            out("Index: ${target.fileName}.")
                        }
                    }
                }
            }
            ":filter" -> {
                // [Day 23] Toggle the improved RAG pipeline (LLM query rewrite + similarity-threshold
                // filter). No arg → show state; on/off → set it. Lets `:rag on` compare baseline vs
                // improved retrieval live.
                val responder = ragResponder
                when {
                    responder == null -> out("RAG is not available (no index/embedder wired).")
                    arg.isEmpty() -> out("Filter (rewrite + threshold): ${if (responder.improved) "on" else "off"}.")
                    else -> when (arg.lowercase()) {
                        "on" -> {
                            responder.improved = true
                            out("Filter (rewrite + threshold): on.")
                        }
                        "off" -> {
                            responder.improved = false
                            out("Filter (rewrite + threshold): off.")
                        }
                        else -> out("Usage: :filter [on|off]")
                    }
                }
            }
            ":ground" -> when (arg.lowercase()) {
                // [Day 25] Toggle whether the AGENT grounds each turn with RAG (task memory + retrieval
                // + history — the production-like chat). Default on. Independent of `:rag` (the standalone
                // stateless path). No arg → show state; on/off → set it.
                "" -> out("Grounding: ${if (groundEnabled) "on" else "off"} (agent uses RAG on every turn).")
                "on" -> {
                    groundEnabled = true
                    out("Grounding: on.")
                }
                "off" -> {
                    groundEnabled = false
                    out("Grounding: off.")
                }
                else -> out("Usage: :ground [on|off]")
            }
            ":llm" -> {
                // [Day 27] Show or switch the generative provider live (mirrors :rag/:ground). A failed
                // switch (e.g. cloud with no API key) keeps the current provider — the app never crashes.
                val sw = llmSwitch
                when {
                    sw == null -> out("Provider switching is not available.")
                    arg.isEmpty() ->
                        out("LLM provider: ${sw.current.label} (local: llama3.2 via Ollama · cloud: Claude).")
                    else -> when (val target = Provider.parse(arg)) {
                        null -> out("Usage: :llm [local|cloud]")
                        else -> when (val result = sw.switchTo(target)) {
                            is SwitchResult.Switched -> out("LLM provider: ${result.to.label}.")
                            is SwitchResult.Unchanged -> out("LLM provider: already ${result.to.label}.")
                            is SwitchResult.Failed ->
                                out("Can't switch to ${result.to.label}: ${result.reason} Staying on ${sw.current.label}.")
                        }
                    }
                }
            }
            ":next" -> advanceAndRun(arg)
            ":remember" -> {
                if (arg.isEmpty()) {
                    out("Usage: :remember <text>")
                } else {
                    memory.longTerm.appendKnowledge(arg)
                    out("Remembered.")
                }
            }
            ":profile-new" -> {
                if (arg.isEmpty()) {
                    out("Usage: :profile-new <name>")
                } else {
                    memory.longTerm.createProfile(arg)
                    out("Created and switched to profile '$arg'.")
                }
            }
            ":profile-switch" -> {
                if (arg.isEmpty()) {
                    out("Usage: :profile-switch <name>")
                } else if (memory.longTerm.switchActiveProfile(arg)) {
                    out("Active profile is now '$arg'.")
                } else {
                    out("No such profile: '$arg'. Use :profile-list to see profiles.")
                }
            }
            ":profile-show" -> out(memory.longTerm.profile())
            ":profile-set" -> {
                val fieldParts = arg.split(" ", limit = 2)
                val field = fieldParts[0]
                val value = fieldParts.getOrNull(1)?.trim().orEmpty()
                if (field.isEmpty() || value.isEmpty()) {
                    out("Usage: :profile-set <field> <value>")
                } else {
                    memory.longTerm.setProfileField(field, value)
                    out("Set $field.")
                }
            }
            ":profile-list" -> printList(
                memory.longTerm.listProfiles(),
                memory.longTerm.activeProfileName(),
                "No profiles yet. Create one with :profile-new <name>.",
            )
            ":invariant-add" -> {
                if (arg.isEmpty()) {
                    out("Usage: :invariant-add <text>")
                } else {
                    memory.invariants.add(arg)
                    out("Added invariant.")
                }
            }
            ":invariant-list" -> printInvariants()
            ":invariant-remove" -> {
                if (arg.isEmpty()) {
                    out("Usage: :invariant-remove <text or index>")
                } else if (memory.invariants.remove(arg)) {
                    out("Removed invariant.")
                } else {
                    out("No matching invariant: '$arg'. Use :invariant-list to see them.")
                }
            }
            ":invariant-clear" -> {
                memory.invariants.clear()
                out("Cleared all invariants.")
            }
            else -> out("Unknown command: $command. Type :help for the list.")
        }
        return false
    }

    /**
     * [3c] `:next [instruction]` — advance to the next stage AND immediately run it. Readiness
     * is read from the PERSISTED header (so a stage completed in a previous session is advanceable
     * after a restart), reusing the SAME TaskStateMachine checks the chain uses: a legal next edge,
     * the artifact ready, and the CODE-owned `stage_complete` flag set. On refusal nothing advances
     * or runs (and [instruction] is ignored). After a successful transition the new stage is run
     * through the normal [chat] path with [instruction] as input (or a neutral default) — EXCEPT
     * advancing INTO DONE, which is terminal and runs no agent turn.
     */
    private suspend fun advanceAndRun(instruction: String) {
        val content = memory.working.activeTaskContent()
        if (content == null) {
            out("No active task. Create one with :task-new <name>.")
            return
        }
        val header = TaskHeader.parse(content)
        val current = header.stage
        // [Day 15] Advance toward the model's PROPOSED, code-validated direction (forward or
        // backward); when none is pending, fall back to the forward successor (Day 13 behavior).
        // Readiness is read from the PERSISTED header, so this works after a restart too.
        val target = header.proposedTransition ?: TaskStateMachine.nextStage(current)
        when {
            target == null ->
                out("Task is already complete (stage '${current.stageValue}'); nothing to advance.")
            !TaskStateMachine.canTransition(current, target) ->
                out(blockedTransition(current, target))
            !TaskStateMachine.isArtifactReady(current, content) ->
                out("Current stage '${current.stageValue}' is not ready to advance: artifact incomplete.")
            !header.stageComplete ->
                out("Current stage '${current.stageValue}' is not ready to advance: stage not marked complete.")
            else -> {
                memory.working.setActiveStage(target.stageValue) // also resets stage_complete + pending proposal
                out(">>> Stage transition: ${current.stageValue} → ${target.stageValue}")
                // DONE is terminal — no agent turn. Otherwise run the new stage now.
                if (target != TaskState.DONE) {
                    chat(instruction.ifBlank { ADVANCE_INPUT })
                }
            }
        }
    }

    /**
     * [Day 15] The code-level BLOCKED-transition message for an illegal [from] → [to], with the
     * legal targets listed FROM the transition table (single source of truth). A forward skip is
     * called out as such; any other illegal move gets a generic phrasing.
     */
    private fun blockedTransition(from: TaskState, to: TaskState): String {
        val allowed = TaskStateMachine.allowedTargets(from)
        val allowedText = if (allowed.isEmpty()) "none" else allowed.joinToString(", ") { it.stageValue }
        val reason = if (to.ordinal > from.ordinal) "that skips stages" else "that transition isn't allowed"
        return ">>> Blocked: can't go ${from.stageValue} → ${to.stageValue} — $reason. " +
            "Allowed from ${from.stageValue}: $allowedText."
    }

    /**
     * [Day 22] RAG-mode turn: retrieve context → grounded LLM answer. [Day 24] The answer is a
     * structured `{answer, citations, dont_know}` call; we print the deterministic sources and the
     * model's VERBATIM citations (each marked verbatim/unverified). When the model judged the context
     * doesn't answer the question it says so and asks to clarify (dontKnow) instead of inventing.
     * Stateless by design — not recorded in short-term memory, so toggling RAG off leaves the agent's
     * session history uncontaminated. One bad call must not kill the REPL.
     */
    private suspend fun ragChat(responder: RagResponder, input: String) {
        try {
            val answer = responder.answer(input, useRag = true)
            printAgentReply(if (answer.dontKnow) "Agent (I don't know)" else "Agent", answer.answer)
            if (answer.sources.isNotEmpty()) {
                out("  sources: [${answer.sources.joinToString(", ")}]")
            }
            if (answer.citations.isNotEmpty()) {
                out("  citations:")
                answer.citations.forEach { c ->
                    val mark = if (c.verbatim) "verbatim" else "unverified"
                    out("    [$mark] \"${c.quote}\" — ${c.source}")
                }
            }
            // [Day 23] When the improved pipeline runs, show the before→after retrieved counts so the
            // filter's effect is visible; the baseline (no filter) leaves them equal.
            if (responder.improved) {
                out("  [retrieved ${answer.retrievedBefore} → kept ${answer.keptAfter}]")
            }
            out("  [tokens: in=${answer.inputTokens}, out=${answer.outputTokens}]")
        } catch (e: Exception) {
            out("Error: ${e.message}")
        }
    }

    private suspend fun chat(input: String) {
        try {
            // [Day 13] The agent runs the stage chain under the current mode; print each
            // step as it happens (real time, not buffered) via the callback.
            // [Day 25] Ground the turn with RAG when grounding is on (the production-like chat).
            val response = agent.run(input, memory.shortTerm.history(), mode, useRag = groundEnabled) { step ->
                printStep(step)
            }
            // Only record the turn once it succeeds, so failed turns don't pollute the
            // session history. The chain's replies collapse into one assistant turn.
            memory.shortTerm.add(Message(Role.USER, input))
            memory.shortTerm.add(Message(Role.ASSISTANT, response.assistantText))

            // [Day 25] ALWAYS show the deterministic sources for a grounded turn (empty when grounding
            // is off / retrieval found nothing) — the production-like chat is sourced every turn.
            if (response.sources.isNotEmpty()) {
                out("  sources: [${response.sources.joinToString(", ")}]")
            }

            // Stage label (from CODE) + token totals at the END — the label reflects the
            // FINAL stage the chain stopped on.
            stageLabel()?.let { out(it) }
            if (response.taskUpdated) {
                out("  [working memory updated: ${memory.working.activeTaskName()}]")
            }
            out("  [tokens: in=${response.inputTokens}, out=${response.outputTokens}]")
        } catch (e: Exception) {
            // One bad call must not kill the REPL.
            out("Error: ${e.message}")
        }
    }

    /**
     * [Day 27] Print an agent answer line from ONE place, prefixed with the active-provider tag
     * (`[local] ` / `[cloud] `) so every answer surface is tagged consistently. The tag is empty when no
     * switch is wired (tests / back-compat), so existing `"Agent: …"` assertions still match. [label] is
     * the speaker prefix (`Agent` or `Agent (I don't know)`).
     */
    private fun printAgentReply(label: String, body: String) {
        val tag = llmSwitch?.let { "[${it.current.label}] " } ?: ""
        out("$tag$label: $body")
    }

    /** Print one chain step (from CODE): reply, refinement, then a performed OR pending transition. */
    private fun printStep(step: ChainStep) {
        printAgentReply("Agent", step.reply)
        step.refinement?.let { r ->
            out(">>> Refining ${r.stage.stageValue} artifact before advancing...")
            printAgentReply("Agent", r.replyText)
        }
        step.transition?.let { t ->
            out(">>> Stage transition: ${t.from.stageValue} → ${t.to.stageValue}")
        }
        step.pendingTransition?.let { t ->
            out(">>> Proposed transition: ${t.from.stageValue} → ${t.to.stageValue}. Type :next to accept.")
        }
    }

    /**
     * The `[stage: <stage> · step: <step>]` label for the active task, or null if
     * there is no active task. `step` is omitted when empty. Built from the task
     * file by CODE so it stays reliable regardless of the model's output.
     */
    private fun stageLabel(): String? {
        val content = memory.working.activeTaskContent() ?: return null
        val header = TaskHeader.parse(content)
        val step = if (header.step.isEmpty()) "" else " · step: ${header.step}"
        return "  [stage: ${header.stage.stageValue}$step]"
    }

    /** Print the invariants as a numbered list (Day 14), or an empty message. */
    private fun printInvariants() {
        val invariants = memory.invariants.list()
        if (invariants.isEmpty()) {
            out("No invariants yet. Add one with :invariant-add <text>.")
        } else {
            invariants.forEachIndexed { i, text -> out("  ${i + 1}. $text") }
        }
    }

    /** Print a list of names with a `* ` marker on the active one (or an empty message). */
    private fun printList(items: List<String>, active: String?, emptyMessage: String) {
        if (items.isEmpty()) {
            out(emptyMessage)
        } else {
            items.forEach { name ->
                val marker = if (name == active) "* " else "  "
                out("$marker$name")
            }
        }
    }

    private fun printHelp() {
        out(
            """
            |Commands:
            |  :task-new <name>     create a task and make it active
            |  :task-switch <name>  switch the active task
            |  :task-list           list tasks (* = active)
            |  :task-show           print the active task file
            |  :stage <name>        set the active task's stage (planning/execution/validation/done)
            |  :mode [auto|confirm] show or set the transition mode (default: confirm)
            |  :ground [on|off]     show or toggle agent grounding (task memory + RAG on every turn; default on)
            |  :rag [on|off]        show or toggle standalone RAG mode (stateless retrieve + grounded answer)
            |  :index [structural|fixed]  show or switch which vector index RAG queries
            |  :filter [on|off]     show or toggle the improved RAG pipeline (query rewrite + threshold filter)
            |  :llm [local|cloud]   show or switch the LLM provider (local llama3.2 via Ollama · cloud Claude)
            |  :next [instruction]  advance to the next stage and run it (optional instruction)
            |  :remember <text>     append a line to long-term knowledge
            |  :profile-new <name>     create an empty profile and make it active
            |  :profile-switch <name>  switch the active profile
            |  :profile-show           print the active profile
            |  :profile-set <f> <v>    set a preference field (overwrites)
            |  :profile-list           list profiles (* = active)
            |  :invariant-add <text>      add a global invariant (hard constraint)
            |  :invariant-list            list invariants (numbered)
            |  :invariant-remove <t|n>    remove an invariant by text or index
            |  :invariant-clear           remove all invariants
            |  :help                show this help
            |  :quit, :q            exit
            |Anything else is sent to the agent as a chat message.
            """.trimMargin(),
        )
    }

    private companion object {
        /** Neutral service input fed to the new stage when `:next` is given no instruction. */
        const val ADVANCE_INPUT = "Proceed with the current stage."
    }
}
