package org.example.digest

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.error
import io.modelcontextprotocol.kotlin.sdk.types.success
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Specs for parsing `get_recent_commits` output into [CollectedCommit]s through a fake [McpClient].
 * Given the tool returns formatted lines, When collected, Then each line maps to a commit; an error
 * result yields an empty list (the loop must not crash).
 */
class CommitCollectorTest {

    @Test
    fun `parses commit lines and passes the arguments through`() = runBlocking {
        val commits = listOf(
            CollectedCommit("a1b2c3d", "Fix flaky test", "Ada", "2026-06-20T10:00:00Z"),
            CollectedCommit("e4f5a6b", "Add feature X", "Linus", "2026-06-19T09:00:00Z"),
        )
        var seenArgs: Map<String, Any?> = emptyMap()
        val client = FakeMcpClient { name, args ->
            assertEquals(CommitCollector.TOOL_NAME, name)
            seenArgs = args
            CallToolResult.success(toolOutput("JetBrains", "kotlin", commits))
        }

        val collected = CommitCollector(client).collect("JetBrains", "kotlin", 5)

        assertEquals(commits, collected)
        assertEquals("JetBrains", seenArgs["owner"])
        assertEquals("kotlin", seenArgs["repo"])
        assertEquals(5, seenArgs["limit"])
    }

    @Test
    fun `omits the limit argument when null`() = runBlocking {
        var seenArgs: Map<String, Any?> = mapOf("limit" to "sentinel")
        val client = FakeMcpClient { _, args ->
            seenArgs = args
            CallToolResult.success(toolOutput("o", "r", emptyList()))
        }

        CommitCollector(client).collect("o", "r", null)

        assertTrue("limit" !in seenArgs, "limit must be absent so the server applies its default")
    }

    @Test
    fun `returns empty on an error result instead of throwing`() = runBlocking {
        val client = FakeMcpClient { _, _ -> CallToolResult.error("404 Not Found") }

        assertTrue(CommitCollector(client).collect("nope", "nope", null).isEmpty())
    }

    @Test
    fun `returns empty when callTool throws`() = runBlocking {
        val client = FakeMcpClient { _, _ -> error("boom") }

        assertTrue(CommitCollector(client).collect("o", "r", null).isEmpty())
    }

    @Test
    fun `parseLine ignores non-commit lines`() {
        assertEquals(null, CommitCollector.parseLine("Recent commits for o/r (2):"))
        assertEquals(null, CommitCollector.parseLine("No commits found for o/r."))
    }

    @Test
    fun `parseLine ignores a line without a leading hex SHA`() {
        // Older-format output (no SHA prefix) must be skipped, not parsed with a fake identity.
        assertEquals(null, CommitCollector.parseLine("- Merge pull request #9 — Ivan (2026-06-24T08:36:53Z)"))
    }
}
