package org.example.mcp.server.transport

import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.example.mcp.server.GitHubMcpServer
import org.example.mcp.server.github.GitHubClient

/**
 * Fallback transport: serves the MCP server over this process's stdio (for launchers that spawn the
 * server as a subprocess). HTTP is primary in Day 17; this exists so the transport choice is a
 * one-place change, mirroring the client-side factory discipline.
 */
class StdioServerTransportFactory(
    private val github: GitHubClient,
) : McpServerTransportFactory {

    override fun start(): McpServerHandle {
        val transport = StdioServerTransport(
            input = System.`in`.asSource().buffered(),
            output = System.out.asSink().buffered(),
        )
        val session = runBlocking { GitHubMcpServer.build(github).createSession(transport) }
        return StdioServerHandle("stdio") {
            runCatching { runBlocking { session.close() } }
            runCatching { github.close() }
        }
    }

    private class StdioServerHandle(
        override val address: String,
        private val stop: () -> Unit,
    ) : McpServerHandle {
        override fun awaitShutdown() {
            // The transport drives the read loop on stdio; block until the stream ends.
            Thread.currentThread().join()
        }

        override fun close() {
            stop()
        }
    }
}
