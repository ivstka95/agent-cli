package org.example

import kotlinx.coroutines.runBlocking
import org.example.agent.Agent
import org.example.agent.CombinedResponseGenerator
import org.example.llm.AnthropicClient
import org.example.memory.MemoryStore
import org.example.repl.Repl
import kotlin.system.exitProcess

/**
 * Entry point: wire the memory store, LLM client, response generator, agent, and
 * REPL together and run the loop.
 */
fun main() = runBlocking {
    val llmClient = try {
        AnthropicClient()
    } catch (e: IllegalStateException) {
        // Missing API key — print a clear instruction and exit, no stack trace.
        System.err.println(e.message)
        exitProcess(1)
    }

    val memory = MemoryStore()
    val generator = CombinedResponseGenerator(llmClient)
    val agent = Agent(generator, memory)
    Repl(agent, memory).start()
}
