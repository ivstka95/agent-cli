package org.example.mcp.server.transport

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import java.util.concurrent.CountDownLatch
import org.example.mcp.server.GitHubMcpServer
import org.example.mcp.server.config.ServerBindConfig
import org.example.mcp.server.github.GitHubClient

/**
 * Primary Day-17 transport: runs the MCP server over HTTP (Ktor SSE) on [bind].
 *
 * `Application.mcp { ... }` installs the SSE plugin + content negotiation and mounts the SSE GET and
 * POST endpoints at the root path, so clients connect to [ServerBindConfig.url]. The [github] client
 * is shared across all SSE sessions; it is closed when the handle is closed.
 *
 * VPS deploy later = change [bind], not this code.
 */
class HttpServerTransportFactory(
    private val bind: ServerBindConfig,
    private val github: GitHubClient,
) : McpServerTransportFactory {

    override fun start(): McpServerHandle {
        val engine = embeddedServer(CIO, host = bind.host, port = bind.port) {
            mcp { GitHubMcpServer.build(github) }
        }
        engine.start(wait = false)
        return HttpServerHandle(bind.url) {
            runCatching { engine.stop(gracePeriodMillis = 0, timeoutMillis = 500) }
            runCatching { github.close() }
        }
    }

    private class HttpServerHandle(
        override val address: String,
        private val stop: () -> Unit,
    ) : McpServerHandle {
        override fun awaitShutdown() {
            val latch = CountDownLatch(1)
            Runtime.getRuntime().addShutdownHook(Thread { latch.countDown() })
            latch.await()
        }

        override fun close() {
            stop()
        }
    }
}
