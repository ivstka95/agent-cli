package org.example.mcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema

/**
 * One MCP tool, decoupled from registration: a name, a description, an input [ToolSchema], and a
 * [handler]. The handler signature mirrors [io.modelcontextprotocol.kotlin.sdk.server.Server.addTool]
 * so [McpToolRegistry] can register it verbatim.
 *
 * Adding a tool later (Day 18) = create one of these and list it in the registry — no wiring changes.
 */
class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: ToolSchema,
    val handler: suspend ClientConnection.(CallToolRequest) -> CallToolResult,
)
