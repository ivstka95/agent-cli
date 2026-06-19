package org.example.repl

import org.example.agent.Agent
import org.example.agent.Message
import org.example.agent.Role

/**
 * Interactive read-eval-print loop. Owns the in-memory session history so that
 * context accumulates within a session; passes the current history to the Agent
 * on every turn. (This is the short-term memory layer that days 11–14 build on.)
 */
class Repl(private val agent: Agent) {

    private val history = mutableListOf<Message>()

    suspend fun start() {
        println("CLI Agent. Type a message, or :quit to exit.")
        while (true) {
            // "You: " is the prompt label; the terminal echoes the typed text onto the
            // same line, so the turn reads "You: <message>". We never re-print the
            // input ourselves — that would duplicate it in an interactive terminal.
            print("You: ")
            val input = readlnOrNull()?.trim()

            // EOF (Ctrl-D / piped input ends): break the prompt line, then exit.
            if (input == null) {
                println()
                println("Bye.")
                return
            }
            if (input == ":quit" || input == ":q") {
                println("Bye.")
                return
            }
            if (input.isEmpty()) continue

            try {
                val response = agent.run(input, history)
                // Only record the turn once the call succeeds, so failed turns
                // don't pollute the history.
                history += Message(Role.USER, input)
                history += Message(Role.ASSISTANT, response.replyText)

                println("Agent: ${response.replyText}")
                println("  [tokens: in=${response.inputTokens}, out=${response.outputTokens}]")
            } catch (e: Exception) {
                // One bad call must not kill the REPL.
                println("Error: ${e.message}")
            }
        }
    }
}
