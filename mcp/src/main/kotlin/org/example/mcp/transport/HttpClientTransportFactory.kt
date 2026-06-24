package org.example.mcp.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport

/**
 * HTTP/SSE client transport: connects to an MCP server over Server-Sent Events at [serverUrl].
 *
 * This is the Day-17 counterpart of [StdioTransportFactory] — the client logic after `connect()`
 * (listTools, callTool) is identical regardless of transport. Pointing at a VPS later = change
 * [serverUrl], nothing else. The Ktor [HttpClient] needs the `SSE` plugin installed; the engine is
 * injectable for tests.
 */
class HttpClientTransportFactory(
    private val serverUrl: String,
    private val engine: HttpClientEngine? = null,
) : McpTransportFactory {

    override fun create(): McpTransportHandle {
        val http = if (engine != null) {
            HttpClient(engine) { install(SSE) }
        } else {
            HttpClient(CIO) { install(SSE) }
        }
        val transport = SseClientTransport(client = http, urlString = serverUrl)
        return HttpTransportHandle(transport, http)
    }

    private class HttpTransportHandle(
        override val transport: SseClientTransport,
        private val http: HttpClient,
    ) : McpTransportHandle {
        override fun close() {
            http.close()
        }
    }
}
