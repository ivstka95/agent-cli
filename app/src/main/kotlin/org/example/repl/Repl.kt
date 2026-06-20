package org.example.repl

import org.example.agent.Agent
import org.example.agent.ChainStep
import org.example.agent.Message
import org.example.agent.Role
import org.example.memory.MemoryStore
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
 */
class Repl(
    private val agent: Agent,
    private val memory: MemoryStore,
    private val out: (String) -> Unit = ::println,
) {

    /** Day 13 / 3c: session transition mode (default CONFIRM); not persisted. */
    private var mode: TransitionMode = TransitionMode.DEFAULT

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
        chat(input)
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
                // Manually set the active task's stage. Validation lives here (against the
                // stage enum) so WorkingMemory stays string-typed.
                val target = TaskState.parse(arg)
                when {
                    arg.isEmpty() ->
                        out("Usage: :stage <planning|execution|validation|done>")
                    target == null ->
                        out("Invalid stage: '$arg'. Valid: planning, execution, validation, done.")
                    memory.working.setActiveStage(target.stageValue) -> {
                        // setActiveStage also resets stage_complete (manual stage → not complete yet).
                        out("Stage set to ${target.stageValue}.")
                    }
                    else ->
                        out("No active task. Create one with :task-new <name>.")
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
        val next = TaskStateMachine.nextStage(current)
        when {
            next == null ->
                out("Task is already complete (stage '${current.stageValue}'); nothing to advance.")
            !TaskStateMachine.isArtifactReady(current, content) ->
                out("Current stage '${current.stageValue}' is not ready to advance: artifact incomplete.")
            !header.stageComplete ->
                out("Current stage '${current.stageValue}' is not ready to advance: stage not marked complete.")
            TaskStateMachine.canTransition(current, next) -> {
                memory.working.setActiveStage(next.stageValue) // also resets stage_complete for the new stage
                out(">>> Stage transition: ${current.stageValue} → ${next.stageValue}")
                // DONE is terminal — no agent turn. Otherwise run the new stage now.
                if (next != TaskState.DONE) {
                    chat(instruction.ifBlank { ADVANCE_INPUT })
                }
            }
        }
    }

    private suspend fun chat(input: String) {
        try {
            // [Day 13] The agent runs the stage chain under the current mode; print each
            // step as it happens (real time, not buffered) via the callback.
            val response = agent.run(input, memory.shortTerm.history(), mode) { step -> printStep(step) }
            // Only record the turn once it succeeds, so failed turns don't pollute the
            // session history. The chain's replies collapse into one assistant turn.
            memory.shortTerm.add(Message(Role.USER, input))
            memory.shortTerm.add(Message(Role.ASSISTANT, response.assistantText))

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

    /** Print one chain step (from CODE): reply, refinement, then a performed OR pending transition. */
    private fun printStep(step: ChainStep) {
        out("Agent: ${step.reply}")
        step.refinement?.let { r ->
            out(">>> Refining ${r.stage.stageValue} artifact before advancing...")
            out("Agent: ${r.replyText}")
        }
        step.transition?.let { t ->
            out(">>> Stage transition: ${t.from.stageValue} → ${t.to.stageValue}")
        }
        step.pendingTransition?.let { t ->
            out(">>> Stage '${t.from.stageValue}' complete and ready to advance to '${t.to.stageValue}'. Type :next to continue.")
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
            |  :next [instruction]  advance to the next stage and run it (optional instruction)
            |  :remember <text>     append a line to long-term knowledge
            |  :profile-new <name>     create an empty profile and make it active
            |  :profile-switch <name>  switch the active profile
            |  :profile-show           print the active profile
            |  :profile-set <f> <v>    set a preference field (overwrites)
            |  :profile-list           list profiles (* = active)
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
