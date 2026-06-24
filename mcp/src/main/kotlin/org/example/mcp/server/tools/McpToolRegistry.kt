package org.example.mcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.example.mcp.server.github.GitHubClient

/**
 * Extensibility hook (Day 18): holds the server's tool definitions and registers them all in one
 * pass. Day 17 ships exactly one tool; adding more = append to [default]'s list — `registerAll`
 * and the server wiring stay untouched.
 */
class McpToolRegistry(private val definitions: List<McpToolDefinition>) {

    /** Registers every definition on [server] via `addTool`. */
    fun registerAll(server: Server) {
        definitions.forEach { def ->
            server.addTool(
                name = def.name,
                description = def.description,
                inputSchema = def.inputSchema,
                handler = def.handler,
            )
        }
    }

    companion object {
        /** The default tool set. Day 18 adds new tools here. */
        fun default(github: GitHubClient): McpToolRegistry =
            McpToolRegistry(
                listOf(
                    GetRecentCommitsTool(github).definition(),
                ),
            )
    }
}
