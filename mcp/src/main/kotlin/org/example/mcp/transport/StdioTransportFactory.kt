package org.example.mcp.transport

import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.example.mcp.config.ServerConfig

/**
 * Stdio transport: launches the configured server as a subprocess and wraps its standard
 * streams in the SDK's [StdioClientTransport].
 *
 * The server command comes from [config] — it is never hardwired here.
 */
class StdioTransportFactory(private val config: ServerConfig) : McpTransportFactory {

    override fun create(): McpTransportHandle {
        val process = ProcessBuilder(config.command)
            // Forward the server's stderr to ours so launch failures (e.g. missing npx) surface.
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        val transport = StdioClientTransport(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
        )
        return StdioTransportHandle(transport, process)
    }

    private class StdioTransportHandle(
        override val transport: StdioClientTransport,
        private val process: Process,
    ) : McpTransportHandle {
        override fun close() {
            process.destroy()
        }
    }
}
