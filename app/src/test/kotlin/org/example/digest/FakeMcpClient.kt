package org.example.digest

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import org.example.mcp.McpClient
import org.example.mcp.ServerInfo

/**
 * Hand-written fake [McpClient] (project rule: fakes over mocks for core interfaces). [responder]
 * decides each `callTool` result from the tool name + arguments, so tests can script per-tick output.
 */
class FakeMcpClient(
    private val responder: (name: String, arguments: Map<String, Any?>) -> CallToolResult,
) : McpClient {
    var connected = false
        private set

    override suspend fun connect() { connected = true }
    override val serverInfo: ServerInfo? = null
    override suspend fun listTools(): List<Tool> = emptyList()
    override suspend fun callTool(name: String, arguments: Map<String, Any?>): CallToolResult =
        responder(name, arguments)
    override suspend fun close() { connected = false }
}
