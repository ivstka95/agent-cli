package org.example.mcp

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.example.mcp.server.config.ServerBindConfig
import org.example.mcp.server.github.GitHubClient
import org.example.mcp.server.transport.HttpServerTransportFactory
import org.example.mcp.transport.HttpClientTransportFactory
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end over HTTP: our GitHub MCP server runs in-process (GitHub stubbed via MockEngine), and a
 * real MCP client connects over SSE and invokes `callTool`. Exercises the full Day-17 path —
 * client transport, SSE wire, server tool dispatch, `CallToolResult` round-trip — minus the network.
 */
class CallToolHttpTest {

    private val commitsJson = """
        [{"sha":"a1","commit":{"message":"Wire up MCP","author":{"name":"Grace","date":"2026-06-21T08:00:00Z"}}}]
    """.trimIndent()

    @Test
    fun `client callTool over HTTP returns commits from the server`() = runBlocking {
        val port = freePort()
        val bind = ServerBindConfig(host = "127.0.0.1", port = port)
        val github = GitHubClient(
            engine = MockEngine {
                respond(
                    content = commitsJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val serverHandle = HttpServerTransportFactory(bind, github).start()
        try {
            waitForPort(bind.host, port)

            val client: McpClient = SdkMcpClient(HttpClientTransportFactory(bind.url))
            try {
                client.connect()

                val tools = client.listTools()
                assertTrue(tools.any { it.name == "get_recent_commits" }, "server should expose the tool")

                val result = client.callTool(
                    "get_recent_commits",
                    mapOf("owner" to "JetBrains", "repo" to "kotlin", "limit" to 1),
                )
                assertEquals(false, result.isError)
                assertTrue(result.textOrError().contains("Wire up MCP"), result.textOrError())
            } finally {
                client.close()
            }
        } finally {
            serverHandle.close()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Wait until the server's TCP port accepts connections (start(wait=false) binds asynchronously). */
    private fun waitForPort(host: String, port: Int, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 200) }
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        error("server did not come up on $host:$port within ${timeoutMs}ms")
    }
}
