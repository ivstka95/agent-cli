package org.example

import kotlinx.coroutines.runBlocking
import org.example.agent.Agent
import org.example.agent.AgenticLoop
import org.example.agent.Ansi
import org.example.agent.CombinedResponseGenerator
import org.example.agent.toToolSpec
import org.example.llm.AnthropicClient
import org.example.mcp.McpClientRegistry
import org.example.mcp.SdkMcpClient
import org.example.mcp.config.ServerConfig
import org.example.mcp.transport.HttpClientTransportFactory
import org.example.mcp.transport.StdioTransportFactory
import org.example.memory.MemoryStore
import org.example.rag.config.RagConfig
import org.example.ragmode.RagResponder
import org.example.repl.Repl
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Entry point: wire the memory store, LLM client, response generator, the Day-20 multi-server MCP
 * orchestration layer, the agentic loop, the agent, and the REPL together and run the loop.
 *
 * [Day 20] The agent connects to TWO MCP servers at once and routes each tool call to the owner:
 *  - our GitHub server over HTTP/SSE (start it first with `./gradlew :mcp:runServer`), and
 *  - the third-party filesystem server over stdio via `npx`, sandboxed to [MCP_FS_DIR] (default
 *    `agent-fs/`).
 * Connection degrades per server: whichever connect contribute their tools; if none do, the agent
 * still runs with plain replies (no tools).
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

    // [Day 20] Orchestrate two MCP servers behind one routing registry.
    val serverUrl = System.getenv("MCP_SERVER_URL")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:3001"
    val fsDir = System.getenv("MCP_FS_DIR")?.takeIf { it.isNotBlank() } ?: "agent-fs"
    // The filesystem server validates its allowed directory at launch, so it must exist first.
    val fsPath = Paths.get(fsDir).toAbsolutePath().normalize()
    runCatching { Files.createDirectories(fsPath) }

    val agentLog: (String) -> Unit = { body -> println(Ansi.agentLine(body)) }
    val registry = McpClientRegistry(
        clients = linkedMapOf(
            "github" to SdkMcpClient(HttpClientTransportFactory(serverUrl)),
            "filesystem" to SdkMcpClient(StdioTransportFactory(ServerConfig.serverFilesystem(fsPath.toString()))),
        ),
        log = agentLog,
    )

    val agenticLoop = try {
        registry.connect()
        val tools = registry.listTools().map { it.toToolSpec() }
        if (tools.isEmpty()) {
            System.err.println(
                "Warning: no MCP tools available (no server reachable). Running without tools — " +
                    "start our server with `./gradlew :mcp:runServer` and ensure `npx` is installed.",
            )
            null
        } else {
            println("MCP tools available (github filesystem): ${tools.joinToString { it.name }}")
            AgenticLoop(llmClient, registry, tools, router = registry)
        }
    } catch (e: Exception) {
        System.err.println("Warning: MCP orchestration failed (${e.message}). Running without tools.")
        null
    }

    val agent = Agent(generator, memory, agenticLoop)

    // [Day 22] Wire the RAG-mode answer path. The index (~10 MB) is loaded lazily on the first RAG
    // query, not at startup; only the embedder's HTTP client is created now. `close()` shuts it down.
    val ragResponder = RagResponder.fromConfig(llmClient, RagConfig.fromEnv())

    try {
        Repl(agent, memory, ragResponder = ragResponder).start()
    } finally {
        registry.close()
        ragResponder.close()
    }
}
