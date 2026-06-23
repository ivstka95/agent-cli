package org.example.mcp

import kotlinx.coroutines.runBlocking
import org.example.mcp.config.ServerConfig
import org.example.mcp.transport.StdioTransportFactory

/**
 * Day 16 demo: connect to an MCP server over stdio and print its tools.
 *
 * Wiring is the only place that picks a transport (stdio) and a server (server-everything);
 * both are swappable without touching [McpClient].
 */
fun main() = runBlocking {
    val config = ServerConfig.serverEverything()
    val client: McpClient = SdkMcpClient(StdioTransportFactory(config))

    try {
        println("Connecting to MCP server: ${config.command.joinToString(" ")}")
        client.connect()

        val server = client.serverInfo
        println(server?.let { "Connected to ${it.name} ${it.version}" } ?: "Connected to <unknown server>")

        val tools = client.listTools()
        println()
        println("Tools (${tools.size}):")
        if (tools.isEmpty()) {
            println("  (none)")
        } else {
            tools.forEach { tool ->
                println("  - ${tool.name}: ${tool.description ?: "(no description)"}")
            }
        }
    } finally {
        client.close()
    }
}
