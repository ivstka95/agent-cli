package org.example.mcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.error
import io.modelcontextprotocol.kotlin.sdk.types.success
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.mcp.server.McpServerLog
import org.example.mcp.textOrError

/**
 * Day-19 pipeline stage 2 (the "process" tool): `build_commit_report(commits)` → a deterministic
 * text report (totals, per-author counts, most-active author, themes). **NO LLM** — pure string
 * processing, so the server stays a thin adapter.
 *
 * Input is the **text output of `get_recent_commits`** passed through by the agent's LLM (this is the
 * data-passing point Day 19 verifies). We parse OUR OWN fixed line format
 * (`- <sha> <message> — <author> (<date>)`); the parser is lenient (skips the header and any line it
 * can't read, never throws) and returns an error result if it finds zero commits so the model reacts.
 */
class BuildCommitReportTool {

    fun definition(): McpToolDefinition = McpToolDefinition(
        name = NAME,
        description =
            "Build a deterministic text report from a list of recent commits. " +
                "Call this AFTER get_recent_commits to summarize activity: it groups commits by " +
                "author, counts totals, finds the most active author, and extracts common themes. " +
                "Pass the FULL text output of get_recent_commits as the 'commits' argument.",
        inputSchema = SCHEMA,
        handler = { request -> handle(request) },
    )

    /** Pure handler, exposed for unit testing without a running server. */
    fun handle(request: CallToolRequest): CallToolResult {
        val args = request.arguments
        val commits = (args?.get("commits") as? JsonPrimitive)?.contentOrNull

        log("tool call: $NAME(commits=${commits?.length ?: 0} chars)")

        if (commits.isNullOrBlank()) {
            return CallToolResult.error("$NAME requires a non-empty 'commits' argument.").also(::logResult)
        }

        val parsed = parse(commits)
        if (parsed.isEmpty()) {
            return CallToolResult.error(
                "$NAME found no parseable commits in input. Expected lines like " +
                    "\"- <sha> <message> — <author> (<date>)\" (the get_recent_commits output).",
            ).also(::logResult)
        }

        return CallToolResult.success(report(parsed)).also(::logResult)
    }

    private fun logResult(result: CallToolResult) = log("result:\n${result.textOrError()}")

    private fun log(body: String) = McpServerLog.line(body)

    // --- Parsing -----------------------------------------------------------------------------

    /** One commit row extracted from a `get_recent_commits` line. */
    private data class ParsedCommit(val message: String, val author: String)

    /**
     * Lenient parse of the `get_recent_commits` text. Skips the header and any line that doesn't fit
     * `- <sha> <message> — <author> (<date>)`. Never throws.
     */
    private fun parse(commits: String): List<ParsedCommit> =
        commits.lineSequence().mapNotNull(::parseLine).toList()

    private fun parseLine(raw: String): ParsedCommit? {
        val line = raw.trim()
        if (!line.startsWith("- ")) return null // header, blank line, or "No commits found …"
        val body = line.removePrefix("- ").trim()

        // Author is after the LAST em-dash separator; the left side is "<sha> <message>".
        val sep = body.lastIndexOf(SEPARATOR)
        if (sep < 0) return null
        val left = body.substring(0, sep).trim()
        val right = body.substring(sep + SEPARATOR.length).trim()

        // Left: first whitespace token = sha, remainder = message subject.
        val firstSpace = left.indexOf(' ')
        if (firstSpace <= 0) return null
        val message = left.substring(firstSpace + 1).trim()
        if (message.isEmpty()) return null

        // Right: strip a trailing "(<date>)" if present; what remains is the author.
        val author = stripTrailingParens(right).trim()
        if (author.isEmpty()) return null

        return ParsedCommit(message, author)
    }

    private fun stripTrailingParens(s: String): String {
        if (!s.endsWith(")")) return s
        val open = s.lastIndexOf('(')
        return if (open >= 0) s.substring(0, open) else s
    }

    // --- Report ------------------------------------------------------------------------------

    private fun report(commits: List<ParsedCommit>): String {
        val byAuthor = commits.groupingBy { it.author }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })

        val themes = countThemes(commits)
        val keywords = topKeywords(commits)

        return buildString {
            appendLine("Commit Report")
            appendLine("=============")
            appendLine("Total commits: ${commits.size}")
            appendLine()
            appendLine("Commits by author:")
            byAuthor.forEach { (author, count) -> appendLine("- $author: $count") }
            appendLine()
            val top = byAuthor.first()
            appendLine("Most active author: ${top.key} (${top.value} commits)")
            appendLine()
            appendLine("Conventional-commit themes:")
            if (themes.isEmpty()) {
                appendLine("- none")
            } else {
                themes.forEach { (type, count) -> appendLine("- $type: $count") }
            }
            appendLine()
            append("Top keywords: ")
            append(if (keywords.isEmpty()) "none" else keywords.joinToString(", ") { "${it.first} (${it.second})" })
        }
    }

    /** Count conventional-commit type prefixes; sorted count desc, then type asc. */
    private fun countThemes(commits: List<ParsedCommit>): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        commits.forEach { c ->
            val type = conventionalType(c.message) ?: return@forEach
            counts[type] = (counts[type] ?: 0) + 1
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }

    /** Leading `[a-z]+` before a `(` or `:` — the conventional `type` / `type(scope):` — if recognized. */
    private fun conventionalType(message: String): String? {
        val head = message.takeWhile { it.isLetter() }.lowercase()
        return head.takeIf { it.isNotEmpty() && it in CONVENTIONAL_TYPES }
    }

    /** Top 5 frequent words; count desc, then word asc. Drops short tokens, stopwords, type words. */
    private fun topKeywords(commits: List<ParsedCommit>): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        commits.forEach { c ->
            c.message.lowercase().split(NON_ALNUM).forEach { token ->
                if (token.length >= MIN_KEYWORD_LEN && token !in STOPWORDS && token !in CONVENTIONAL_TYPES) {
                    counts[token] = (counts[token] ?: 0) + 1
                }
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_KEYWORDS)
            .map { it.key to it.value }
    }

    companion object {
        const val NAME = "build_commit_report"

        private const val SEPARATOR = " — " // em dash (U+2014), as emitted by GetRecentCommitsTool.format()
        private const val TOP_KEYWORDS = 5
        private const val MIN_KEYWORD_LEN = 3
        private val NON_ALNUM = Regex("[^a-z0-9]+")

        private val CONVENTIONAL_TYPES = setOf(
            "feat", "fix", "docs", "chore", "refactor", "test", "ci", "build", "perf", "style",
        )

        private val STOPWORDS = setOf(
            "the", "and", "for", "with", "from", "into", "this", "that", "out", "add", "added",
            "use", "via", "not", "now", "are", "was", "but", "can", "all", "new", "when",
        )

        private val SCHEMA: ToolSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("commits") {
                    put("type", "string")
                    put(
                        "description",
                        "The full text output of get_recent_commits (one '- <sha> <message> — " +
                            "<author> (<date>)' line per commit). Pass it through verbatim.",
                    )
                }
            },
            required = listOf("commits"),
        )
    }
}
