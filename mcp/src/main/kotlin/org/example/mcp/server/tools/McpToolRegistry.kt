package org.example.mcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.example.mcp.server.github.GitHubClient
import java.nio.file.Path

/**
 * Extensibility hook (Day 18): holds the server's tool definitions and registers them all in one
 * pass. Day 17 shipped one tool; Day 19 adds two more — adding a tool = append to [default]'s list,
 * `registerAll` and the server wiring stay untouched.
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
        /**
         * The default tool set: the Day-17 GitHub tool plus the Day-19 pipeline pair
         * (`build_commit_report` is pure; `save_to_file` writes under [outputDir]).
         */
        fun default(github: GitHubClient, outputDir: Path): McpToolRegistry =
            McpToolRegistry(
                listOf(
                    GetRecentCommitsTool(github).definition(),
                    BuildCommitReportTool().definition(),
                    SaveToFileTool(outputDir).definition(),
                ),
            )
    }
}
