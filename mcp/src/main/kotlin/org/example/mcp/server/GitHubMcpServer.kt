package org.example.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.example.mcp.server.github.GitHubClient
import org.example.mcp.server.tools.McpToolRegistry

/**
 * Builds our MCP [Server] wrapping the public GitHub API. The server declares the `tools`
 * capability and registers everything in [McpToolRegistry]; the [github] client is shared across
 * tool calls (one instance per process, supplied by the transport factory).
 */
object GitHubMcpServer {

    const val NAME = "agent-cli-github"
    const val VERSION = "0.1.0"

    fun build(github: GitHubClient): Server {
        val server = Server(
            serverInfo = Implementation(name = NAME, version = VERSION),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = null)),
            ),
        )
        McpToolRegistry.default(github).registerAll(server)
        return server
    }
}
