package org.example.repl

import org.example.agent.Agent
import org.example.agent.Message
import org.example.agent.Role
import org.example.memory.MemoryStore

/**
 * Interactive read-eval-print loop.
 *
 * Owns no history itself — short-term memory lives in [MemoryStore.shortTerm],
 * which accumulates the session dialog and is passed to the Agent each turn.
 * Also handles the memory commands (working-memory tasks + long-term :remember).
 */
class Repl(
    private val agent: Agent,
    private val memory: MemoryStore,
) {

    suspend fun start() {
        println("CLI Agent. Type a message, :help for commands, or :quit to exit.")
        while (true) {
            // "You: " is the prompt label; the terminal echoes the typed text onto
            // the same line, so the turn reads "You: <message>". We never re-print
            // the input ourselves — that would duplicate it in an interactive terminal.
            print("You: ")
            val input = readlnOrNull()?.trim()

            // EOF (Ctrl-D / piped input ends): break the prompt line, then exit.
            if (input == null) {
                println()
                break
            }
            if (input.isEmpty()) continue
            if (input.startsWith(":")) {
                if (handleCommand(input)) break // command signalled exit
                continue
            }

            chat(input)
        }
        println("Bye.")
    }

    /** Handle a `:`-prefixed command. Returns true if the REPL should exit. */
    private fun handleCommand(input: String): Boolean {
        val parts = input.split(" ", limit = 2)
        val command = parts[0]
        val arg = parts.getOrNull(1)?.trim().orEmpty()

        when (command) {
            ":quit", ":q" -> return true
            ":help" -> printHelp()
            ":task-new" -> {
                if (arg.isEmpty()) {
                    println("Usage: :task-new <name>")
                } else {
                    memory.working.createTask(arg)
                    println("Created and switched to task '$arg'.")
                }
            }
            ":task-switch" -> {
                if (arg.isEmpty()) {
                    println("Usage: :task-switch <name>")
                } else if (memory.working.switchActive(arg)) {
                    println("Active task is now '$arg'.")
                } else {
                    println("No such task: '$arg'. Use :task-list to see tasks.")
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
                    println("No active task. Create one with :task-new <name>.")
                } else {
                    println(content)
                }
            }
            ":remember" -> {
                if (arg.isEmpty()) {
                    println("Usage: :remember <text>")
                } else {
                    memory.longTerm.appendKnowledge(arg)
                    println("Remembered.")
                }
            }
            ":profile-new" -> {
                if (arg.isEmpty()) {
                    println("Usage: :profile-new <name>")
                } else {
                    memory.longTerm.createProfile(arg)
                    println("Created and switched to profile '$arg'.")
                }
            }
            ":profile-switch" -> {
                if (arg.isEmpty()) {
                    println("Usage: :profile-switch <name>")
                } else if (memory.longTerm.switchActiveProfile(arg)) {
                    println("Active profile is now '$arg'.")
                } else {
                    println("No such profile: '$arg'. Use :profile-list to see profiles.")
                }
            }
            ":profile-show" -> println(memory.longTerm.profile())
            ":profile-set" -> {
                val fieldParts = arg.split(" ", limit = 2)
                val field = fieldParts[0]
                val value = fieldParts.getOrNull(1)?.trim().orEmpty()
                if (field.isEmpty() || value.isEmpty()) {
                    println("Usage: :profile-set <field> <value>")
                } else {
                    memory.longTerm.setProfileField(field, value)
                    println("Set $field.")
                }
            }
            ":profile-list" -> printList(
                memory.longTerm.listProfiles(),
                memory.longTerm.activeProfileName(),
                "No profiles yet. Create one with :profile-new <name>.",
            )
            else -> println("Unknown command: $command. Type :help for the list.")
        }
        return false
    }

    private suspend fun chat(input: String) {
        try {
            val response = agent.run(input, memory.shortTerm.history())
            // Only record the turn once the call succeeds, so failed turns don't
            // pollute the session history.
            memory.shortTerm.add(Message(Role.USER, input))
            memory.shortTerm.add(Message(Role.ASSISTANT, response.replyText))

            println("Agent: ${response.replyText}")
            if (response.taskUpdated) {
                println("  [working memory updated: ${memory.working.activeTaskName()}]")
            }
            println("  [tokens: in=${response.inputTokens}, out=${response.outputTokens}]")
        } catch (e: Exception) {
            // One bad call must not kill the REPL.
            println("Error: ${e.message}")
        }
    }

    /** Print a list of names with a `* ` marker on the active one (or an empty message). */
    private fun printList(items: List<String>, active: String?, emptyMessage: String) {
        if (items.isEmpty()) {
            println(emptyMessage)
        } else {
            items.forEach { name ->
                val marker = if (name == active) "* " else "  "
                println("$marker$name")
            }
        }
    }

    private fun printHelp() {
        println(
            """
            |Commands:
            |  :task-new <name>     create a task and make it active
            |  :task-switch <name>  switch the active task
            |  :task-list           list tasks (* = active)
            |  :task-show           print the active task file
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
}
