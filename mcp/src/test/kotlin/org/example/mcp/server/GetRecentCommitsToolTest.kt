package org.example.mcp.server

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.mcp.textOrError
import org.example.mcp.server.github.GitHubClient
import org.example.mcp.server.tools.GetRecentCommitsTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit-tests the `get_recent_commits` handler with the GitHub API stubbed by a Ktor MockEngine —
 * no network, no running MCP server. Verifies the happy path formats commits and the error path
 * returns a non-throwing error result.
 */
class GetRecentCommitsToolTest {

    private val commitsJson = """
        [
          {"sha":"a1","commit":{"message":"Fix flaky test\n\nbody","author":{"name":"Ada","date":"2026-06-20T10:00:00Z"}}},
          {"sha":"b2","commit":{"message":"Add feature X","author":{"name":"Linus","date":"2026-06-19T09:00:00Z"}}}
        ]
    """.trimIndent()

    @Test
    fun `returns formatted commits on success`() = runBlocking {
        val engine = MockEngine { request ->
            assertTrue(request.url.toString().contains("/repos/JetBrains/kotlin/commits"))
            assertTrue(request.url.toString().contains("per_page=2"))
            respond(
                content = commitsJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val tool = GetRecentCommitsTool(GitHubClient(engine = engine))

        val result = tool.handle(request("JetBrains", "kotlin", 2))
        val text = result.textOrError()

        assertEquals(false, result.isError)
        // First line of the message only (subject), with author and date.
        assertTrue(text.contains("Fix flaky test"), text)
        assertTrue(!text.contains("body"), "should keep only the commit subject line")
        assertTrue(text.contains("Ada"), text)
        assertTrue(text.contains("2026-06-20T10:00:00Z"), text)
        assertTrue(text.contains("Add feature X"), text)
    }

    @Test
    fun `returns an error result on a 404 (unknown repo)`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"message":"Not Found"}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val tool = GetRecentCommitsTool(GitHubClient(engine = engine))

        val result = tool.handle(request("nope", "nope", 5))

        assertEquals(true, result.isError)
        assertTrue(result.textOrError().contains("404"), result.textOrError())
    }

    @Test
    fun `returns an error result when owner or repo is missing`() = runBlocking {
        // No HTTP call should happen; a never-responding engine would fail if reached.
        val engine = MockEngine { error("HTTP should not be called when arguments are invalid") }
        val tool = GetRecentCommitsTool(GitHubClient(engine = engine))

        val result = tool.handle(
            CallToolRequest(CallToolRequestParams(name = GetRecentCommitsTool.NAME, arguments = buildJsonObject { put("repo", "kotlin") })),
        )

        assertEquals(true, result.isError)
    }

    private fun request(owner: String, repo: String, limit: Int): CallToolRequest =
        CallToolRequest(
            CallToolRequestParams(
                name = GetRecentCommitsTool.NAME,
                arguments = buildJsonObject {
                    put("owner", owner)
                    put("repo", repo)
                    put("limit", JsonPrimitive(limit))
                },
            ),
        )
}
