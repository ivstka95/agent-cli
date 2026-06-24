package org.example

import kotlinx.coroutines.runBlocking
import org.example.agent.Agent
import org.example.agent.AgenticLoop
import org.example.agent.CombinedResponseGenerator
import org.example.agent.toToolSpec
import org.example.llm.AnthropicClient
import org.example.mcp.McpClient
import org.example.mcp.SdkMcpClient
import org.example.mcp.transport.HttpClientTransportFactory
import org.example.memory.MemoryStore
import org.example.repl.Repl
import kotlin.system.exitProcess

/**
 * Entry point: wire the memory store, LLM client, response generator, the Day-17 MCP-backed
 * agentic loop, the agent, and the REPL together and run the loop.
 *
 * Run order: start the MCP server first (`./gradlew :mcp:run`), then this app. If the server is
 * unreachable, the agent still runs — it just falls back to plain replies (no tools).
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

    // [Day 17] Connect to our GitHub MCP server over HTTP, discover its tools, and build the
    // agentic loop. Tools come from the live server (listTools) — no hardcoded schema here.
    val serverUrl = System.getenv("MCP_SERVER_URL")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:3001"
    val mcpClient: McpClient = SdkMcpClient(HttpClientTransportFactory(serverUrl))
    val agenticLoop = try {
        mcpClient.connect()
        val tools = mcpClient.listTools().map { it.toToolSpec() }
        println("Connected to MCP server at $serverUrl — tools: ${tools.joinToString { it.name }}")
        AgenticLoop(llmClient, mcpClient, tools)
    } catch (e: Exception) {
        System.err.println(
            "Warning: could not reach MCP server at $serverUrl (${e.message}). " +
                "Running without tools — start it with `./gradlew :mcp:run`.",
        )
        null
    }

    val agent = Agent(generator, memory, agenticLoop)
    try {
        Repl(agent, memory).start()
    } finally {
        mcpClient.close()
    }
}
