package org.example.mcp

import kotlinx.coroutines.runBlocking
import org.example.mcp.config.ServerConfig
import org.example.mcp.transport.StdioTransportFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration test against the real `server-everything` over stdio. Requires Node/npx at test
 * time; skips gracefully (does not fail) when npx is unavailable on the runner.
 */
class McpClientStdioTest {

    @Test
    fun `connects and lists tools from server-everything`() = runBlocking {
        assumeTrue(npxAvailable(), "npx not available — skipping MCP stdio integration test")

        val client: McpClient = SdkMcpClient(StdioTransportFactory(ServerConfig.serverEverything()))
        try {
            client.connect()
            val tools = client.listTools()

            assertTrue(tools.isNotEmpty(), "expected a non-empty tool list from server-everything")
            assertTrue(
                tools.any { it.name == "echo" },
                "expected server-everything to expose an 'echo' tool; got ${tools.map { it.name }}",
            )
        } finally {
            client.close()
        }
    }

    private fun npxAvailable(): Boolean = try {
        ProcessBuilder("npx", "--version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (_: Exception) {
        false
    }
}
