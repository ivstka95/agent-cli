package org.example.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import org.example.mcp.transport.McpTransportFactory
import org.example.mcp.transport.McpTransportHandle

/** The connected server's identity, available after [McpClient.connect]. */
data class ServerInfo(val name: String, val version: String)

/**
 * Small abstraction over the MCP SDK [Client].
 *
 * Transport- and server-agnostic: the transport is supplied by an [McpTransportFactory] and the
 * server command lives in config, so everything after [connect] is identical regardless of which
 * transport or server is used.
 *
 * Day 16 scope = list tools only. A `callTool()` seat is reserved below for Day 17.
 */
interface McpClient {
    /** Launches the transport and performs the MCP initialization handshake. */
    suspend fun connect()

    /** The connected server's name/version; `null` until [connect] succeeds. */
    val serverInfo: ServerInfo?

    /**
     * Returns the tools advertised by the server. The SDK [Tool] type is intentionally exposed
     * raw for Day 16's list-only scope; a thin mapping type (mirroring [ServerInfo]) can be
     * introduced when a second consumer or Day 17's `callTool` needs it.
     */
    suspend fun listTools(): List<Tool>

    /**
     * Invokes a tool on the connected server, reusing the SAME [connect]ed session.
     * [arguments] keys/values must match the tool's input schema.
     */
    suspend fun callTool(name: String, arguments: Map<String, Any?>): CallToolResult

    /** Releases the connection and the underlying transport/subprocess. */
    suspend fun close()
}

/**
 * Extracts the textual content of a [CallToolResult] (concatenated [TextContent] blocks), so
 * callers don't touch the SDK's content-block polymorphism. If [CallToolResult.isError] is true,
 * the text is prefixed with `Error: ` so the marker survives into a plain string.
 */
fun CallToolResult.textOrError(): String {
    val text = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    return if (isError == true) "Error: $text" else text
}

/**
 * Default [McpClient] backed by the official SDK [Client]. The transport is obtained lazily from
 * the injected [transportFactory] on [connect]; this class never references stdio directly.
 */
class SdkMcpClient(
    private val transportFactory: McpTransportFactory,
    clientName: String = "agent-cli-mcp",
    clientVersion: String = "0.1.0",
) : McpClient {

    private val client = Client(
        clientInfo = Implementation(name = clientName, version = clientVersion),
    )
    private var handle: McpTransportHandle? = null

    override val serverInfo: ServerInfo?
        get() = client.serverVersion?.let { ServerInfo(it.name, it.version) }

    override suspend fun connect() {
        val created = transportFactory.create()
        handle = created
        client.connect(created.transport)
    }

    override suspend fun listTools(): List<Tool> = client.listTools().tools

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): CallToolResult =
        client.callTool(name = name, arguments = arguments)

    override suspend fun close() {
        // Best-effort: close the SDK client (closes the transport), then the handle (which
        // terminates the subprocess). Either may throw if never connected — ignore.
        runCatching { client.close() }
        runCatching { handle?.close() }
    }
}
