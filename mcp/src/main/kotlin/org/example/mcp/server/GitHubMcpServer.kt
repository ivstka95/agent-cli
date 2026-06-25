package org.example.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.example.mcp.server.github.GitHubClient
import org.example.mcp.server.tools.McpToolRegistry
import java.nio.file.Path

/**
 * Builds our MCP [Server] wrapping the public GitHub API. The server declares the `tools`
 * capability and registers everything in [McpToolRegistry]; the [github] client is shared across
 * tool calls (one instance per process, supplied by the transport factory). [outputDir] is the
 * protected base directory the Day-19 `save_to_file` tool writes into.
 */
object GitHubMcpServer {

    const val NAME = "agent-cli-github"
    const val VERSION = "0.1.0"

    fun build(github: GitHubClient, outputDir: Path): Server {
        val server = Server(
            serverInfo = Implementation(name = NAME, version = VERSION),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = null)),
            ),
        )
        McpToolRegistry.default(github, outputDir).registerAll(server)
        return server
    }
}
