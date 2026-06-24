package org.example.digest

import org.example.agent.Ansi
import org.example.mcp.McpClient
import org.example.mcp.textOrError

/**
 * The ONE place that turns `get_recent_commits` text output into [CollectedCommit]s. It calls the
 * existing Day-17 tool through the shared [McpClient] (reusing the same connection/`callTool` path)
 * and parses each line of the form `- {sha7} {message} — {author} ({date})`.
 *
 * Never throws: a transport failure or an `Error:`-prefixed tool result yields an empty list (logged),
 * so one bad tick can't crash the 24/7 loop.
 */
class CommitCollector(private val mcpClient: McpClient) {

    suspend fun collect(owner: String, repo: String, limit: Int?): List<CollectedCommit> {
        val args = buildMap<String, Any?> {
            put("owner", owner)
            put("repo", repo)
            if (limit != null) put("limit", limit)
        }

        val text = runCatching { mcpClient.callTool(TOOL_NAME, args).textOrError() }
            .getOrElse { e ->
                log("collect failed: ${e.message}")
                return emptyList()
            }
        if (text.startsWith("Error:")) {
            log("tool returned an error: $text")
            return emptyList()
        }
        return text.lineSequence().mapNotNull(::parseLine).toList()
    }

    private fun log(body: String) = println(Ansi.bold(Ansi.green("[DIGEST]")) + " " + Ansi.green(body))

    companion object {
        const val TOOL_NAME = "get_recent_commits"

        // `- {sha} {message} — {author} ({date})`. The leading token must be a hex SHA so a line
        // WITHOUT one (e.g. an older server's output) is skipped rather than mis-parsed with the
        // first message word as a fake identity. Author excludes '(' so the trailing `(date)` is
        // matched independently; message is greedy up to the last em dash before the author.
        private val LINE = Regex("""^- ([0-9a-f]{7,40}) (.*) — ([^(]*) \(([^)]*)\)$""")

        /** Parses one tool-output line into a [CollectedCommit], or `null` for non-commit lines. */
        internal fun parseLine(line: String): CollectedCommit? {
            val match = LINE.matchEntire(line.trim()) ?: return null
            val (sha, message, author, date) = match.destructured
            return CollectedCommit(sha.trim(), message.trim(), author.trim(), date.trim())
        }
    }
}
