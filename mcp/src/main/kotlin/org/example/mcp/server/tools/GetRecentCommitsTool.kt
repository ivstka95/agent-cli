package org.example.mcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.error
import io.modelcontextprotocol.kotlin.sdk.types.success
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.mcp.server.McpServerLog
import org.example.mcp.textOrError
import org.example.mcp.server.github.Commit
import org.example.mcp.server.github.GitHubApiException
import org.example.mcp.server.github.GitHubClient

/**
 * The Day-17 tool: `get_recent_commits(owner, repo, limit?)` → recent commits from the public
 * GitHub API (message, author, date). The handler never throws out — GitHub/argument failures are
 * returned as `CallToolResult.error(...)` so the model sees them and can adjust.
 */
class GetRecentCommitsTool(private val github: GitHubClient) {

    fun definition(): McpToolDefinition = McpToolDefinition(
        name = NAME,
        description =
            "Get recent commits for a public GitHub repository. " +
                "Call this when the user asks about latest/recent commits, changes, or activity " +
                "in a specific repo. Returns each commit's message, author, and date.",
        inputSchema = SCHEMA,
        handler = { request -> handle(request) },
    )

    /** Pure handler, exposed for unit testing without a running server. */
    suspend fun handle(request: CallToolRequest): CallToolResult {
        val args = request.arguments
        val owner = (args?.get("owner") as? JsonPrimitive)?.contentOrNull
        val repo = (args?.get("repo") as? JsonPrimitive)?.contentOrNull
        val limitArg = (args?.get("limit") as? JsonPrimitive)?.intOrNull

        // [Day 17] Color-highlighted transparency log (visible in the :mcp:runServer terminal). Full input.
        log(
            "tool call: $NAME(owner=$owner, repo=$repo, " +
                "limit=${limitArg ?: "default($DEFAULT_LIMIT)"})  args=${args ?: "{}"}",
        )

        if (owner.isNullOrBlank() || repo.isNullOrBlank()) {
            return CallToolResult.error("$NAME requires non-empty 'owner' and 'repo' arguments.").also(::logResult)
        }
        val limit = (limitArg ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        return try {
            val commits = github.recentCommits(owner, repo, limit)
            if (commits.isEmpty()) {
                CallToolResult.success("No commits found for $owner/$repo.")
            } else {
                CallToolResult.success(format(owner, repo, commits))
            }
        } catch (e: GitHubApiException) {
            CallToolResult.error("GitHub API error ${e.statusCode} for $owner/$repo: ${e.shortBody()}")
        } catch (e: Exception) {
            CallToolResult.error("Failed to fetch commits for $owner/$repo: ${e.message}")
        }.also(::logResult)
    }

    /** [Day 17] Logs the FULL result text (or error) in cyan to the :mcp:runServer terminal. */
    private fun logResult(result: CallToolResult) = log("result:\n${result.textOrError()}")

    /** [Day 17/19] One cyan, bold-prefixed `[MCP SERVER]` line via the shared logger. */
    private fun log(body: String) = McpServerLog.line(body)

    private fun format(owner: String, repo: String, commits: List<Commit>): String = buildString {
        appendLine("Recent commits for $owner/$repo (${commits.size}):")
        commits.forEach { c ->
            // Short SHA leads the line so a programmatic consumer (the Day-18 digest) has a stable
            // commit identity to diff on; humans/the model still read the message/author/date after it.
            appendLine("- ${c.sha.take(7)} ${c.message} — ${c.author} (${c.date})")
        }
    }.trimEnd()

    companion object {
        const val NAME = "get_recent_commits"
        private const val DEFAULT_LIMIT = 10
        private const val MAX_LIMIT = 30

        private val SCHEMA: ToolSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("owner") {
                    put("type", "string")
                    put("description", "Repository owner or organization, e.g. \"JetBrains\".")
                }
                putJsonObject("repo") {
                    put("type", "string")
                    put("description", "Repository name, e.g. \"kotlin\".")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many recent commits to return (1–30, default 10).")
                }
            },
            required = listOf("owner", "repo"),
        )
    }
}
