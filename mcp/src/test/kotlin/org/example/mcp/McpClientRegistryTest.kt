package org.example.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.success
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Day 20 — the multi-server orchestration layer. A [McpClientRegistry] fronts several named
 * [McpClient]s: it merges their advertised tools and routes each `callTool` to the owning server.
 * These tests use hand-written fakes (no network, no npx) per the project's fake-over-mock rule.
 */
class McpClientRegistryTest {

    /** A minimal fake client advertising [toolNames]; records every routed [callTool]. */
    private class FakeMcpClient(
        private val toolNames: List<String>,
        private val name: String = "fake",
        private val failOnConnect: Boolean = false,
    ) : McpClient {
        var connected = false
        val calls = mutableListOf<String>()

        override val serverInfo: ServerInfo? get() = if (connected) ServerInfo(name, "1.0") else null

        override suspend fun connect() {
            if (failOnConnect) throw IllegalStateException("boom: $name unreachable")
            connected = true
        }

        override suspend fun listTools(): List<Tool> =
            toolNames.map { Tool(name = it, inputSchema = ToolSchema()) }

        override suspend fun callTool(name: String, arguments: Map<String, Any?>): CallToolResult {
            calls += name
            return CallToolResult.success("$name handled by ${this.name}")
        }

        override suspend fun close() {}
    }

    @Test
    fun `listTools merges tools from every connected server`() = runBlocking {
        val github = FakeMcpClient(listOf("get_recent_commits", "build_commit_report"), "github")
        val fs = FakeMcpClient(listOf("write_file", "read_file"), "filesystem")
        val registry = McpClientRegistry(mapOf("github" to github, "filesystem" to fs))

        registry.connect()
        val names = registry.listTools().map { it.name }

        assertEquals(
            listOf("get_recent_commits", "build_commit_report", "write_file", "read_file"),
            names,
        )
    }

    @Test
    fun `callTool routes to the server that owns the tool`() = runBlocking {
        val github = FakeMcpClient(listOf("get_recent_commits"), "github")
        val fs = FakeMcpClient(listOf("write_file"), "filesystem")
        val registry = McpClientRegistry(mapOf("github" to github, "filesystem" to fs))
        registry.connect()
        registry.listTools()

        val result = registry.callTool("write_file", mapOf("path" to "x.md"))

        assertEquals("write_file handled by filesystem", result.textOrError())
        assertEquals(listOf("write_file"), fs.calls)
        assertTrue(github.calls.isEmpty())
    }

    @Test
    fun `callTool for an unknown tool throws`() = runBlocking {
        val registry = McpClientRegistry(mapOf("github" to FakeMcpClient(listOf("get_recent_commits"))))
        registry.connect()
        registry.listTools()

        assertFailsWith<IllegalArgumentException> {
            registry.callTool("nope", emptyMap())
        }
        Unit
    }

    @Test
    fun `serverFor names the owning server and is null for unknown tools`() = runBlocking {
        val github = FakeMcpClient(listOf("get_recent_commits"), "github")
        val fs = FakeMcpClient(listOf("write_file"), "filesystem")
        val registry = McpClientRegistry(mapOf("github" to github, "filesystem" to fs))
        registry.connect()
        registry.listTools()

        assertEquals("github", registry.serverFor("get_recent_commits"))
        assertEquals("filesystem", registry.serverFor("write_file"))
        assertNull(registry.serverFor("nope"))
    }

    @Test
    fun `name collision keeps the first-registered owner and warns`() = runBlocking {
        val first = FakeMcpClient(listOf("shared"), "github")
        val second = FakeMcpClient(listOf("shared"), "filesystem")
        val warnings = mutableListOf<String>()
        val registry = McpClientRegistry(
            mapOf("github" to first, "filesystem" to second),
            log = { warnings += it },
        )
        registry.connect()

        val names = registry.listTools().map { it.name }

        assertEquals(listOf("shared"), names)
        assertEquals("github", registry.serverFor("shared"))
        registry.callTool("shared", emptyMap())
        assertEquals(listOf("shared"), first.calls)
        assertTrue(second.calls.isEmpty())
        assertTrue(warnings.any { it.contains("shared") }, "expected a collision warning")
    }

    @Test
    fun `a server that fails to connect is skipped and the rest still serve tools`() = runBlocking {
        val github = FakeMcpClient(listOf("get_recent_commits"), "github")
        val fs = FakeMcpClient(listOf("write_file"), "filesystem", failOnConnect = true)
        val registry = McpClientRegistry(mapOf("github" to github, "filesystem" to fs))

        registry.connect()
        val names = registry.listTools().map { it.name }

        assertEquals(listOf("get_recent_commits"), names)
        assertNull(registry.serverFor("write_file"))
    }
}
