package org.example.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.mcp.server.tools.BuildCommitReportTool
import org.example.mcp.textOrError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit-tests the Day-19 `build_commit_report` handler. Pure & deterministic — no network, no LLM, no
 * running server. Input is the `get_recent_commits` text format the agent's LLM passes through.
 */
class BuildCommitReportToolTest {

    private val tool = BuildCommitReportTool()

    @Test
    fun `produces a deterministic report (totals, authors, tie-break, themes, keywords)`() {
        // Ada=2, Bob=2 → tie on count; most-active tie-break = name ascending → Ada.
        val commits = """
            Recent commits for JetBrains/kotlin (4):
            - a1 fix: parser edge case — Bob (2026-06-20T10:00:00Z)
            - b2 feat: parser streaming — Ada (2026-06-19T09:00:00Z)
            - c3 fix: parser guard — Ada (2026-06-18T08:00:00Z)
            - d4 docs: parser notes — Bob (2026-06-17T07:00:00Z)
        """.trimIndent()

        val result = tool.handle(request(commits))
        assertEquals(false, result.isError, result.textOrError())

        val expected = """
            Commit Report
            =============
            Total commits: 4

            Commits by author:
            - Ada: 2
            - Bob: 2

            Most active author: Ada (2 commits)

            Conventional-commit themes:
            - fix: 2
            - docs: 1
            - feat: 1

            Top keywords: parser (4), case (1), edge (1), guard (1), notes (1)
        """.trimIndent()

        assertEquals(expected, result.textOrError())
    }

    @Test
    fun `skips the header and unparseable lines`() {
        val commits = """
            Recent commits for JetBrains/kotlin (2):
            - a1 fix: real commit — Ada (2026-06-20T10:00:00Z)
            this line is not a commit
            - b2 feat: another real one — Linus (2026-06-19T09:00:00Z)
            No commits found for x/y.
        """.trimIndent()

        val text = tool.handle(request(commits)).textOrError()

        // Only the two valid "- …" lines are counted; header / prose / "No commits" are ignored.
        assertTrue(text.contains("Total commits: 2"), text)
        assertTrue(text.contains("- Ada: 1"), text)
        assertTrue(text.contains("- Linus: 1"), text)
    }

    @Test
    fun `returns an error result when nothing is parseable`() {
        val commits = """
            Recent commits for JetBrains/kotlin (0):
            No commits found for JetBrains/kotlin.
        """.trimIndent()

        val result = tool.handle(request(commits))

        assertEquals(true, result.isError)
        assertTrue(result.textOrError().contains("no parseable commits"), result.textOrError())
    }

    @Test
    fun `returns an error result when commits argument is missing`() {
        val result = tool.handle(
            CallToolRequest(CallToolRequestParams(name = BuildCommitReportTool.NAME, arguments = buildJsonObject {})),
        )
        assertEquals(true, result.isError)
    }

    private fun request(commits: String): CallToolRequest =
        CallToolRequest(
            CallToolRequestParams(
                name = BuildCommitReportTool.NAME,
                arguments = buildJsonObject { put("commits", commits) },
            ),
        )
}
