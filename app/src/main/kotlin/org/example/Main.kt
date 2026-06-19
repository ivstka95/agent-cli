package org.example

import kotlinx.coroutines.runBlocking
import org.example.agent.Agent
import org.example.llm.AnthropicClient
import org.example.repl.Repl
import kotlin.system.exitProcess

/**
 * Entry point: wire AnthropicClient -> Agent -> Repl and run the loop.
 */
fun main() = runBlocking {
    val llmClient = try {
        AnthropicClient()
    } catch (e: IllegalStateException) {
        // Missing API key — print a clear instruction and exit, no stack trace.
        System.err.println(e.message)
        exitProcess(1)
    }

    val agent = Agent(llmClient)
    Repl(agent).start()
}
