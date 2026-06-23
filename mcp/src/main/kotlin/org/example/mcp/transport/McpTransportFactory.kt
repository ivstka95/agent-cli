package org.example.mcp.transport

import io.modelcontextprotocol.kotlin.sdk.shared.Transport

/**
 * Creates the MCP [Transport]. This is the single seam that hides HOW we connect.
 *
 * The client logic after `connect()` (e.g. `listTools()`) is transport-agnostic, so a future
 * HTTP/SSE transport (Day 18+, for a remote server) slots in by adding one more factory
 * implementation and changing which factory is constructed — one place, nothing else.
 */
interface McpTransportFactory {
    fun create(): McpTransportHandle
}

/**
 * A created transport together with the resources backing it (e.g. a launched subprocess).
 *
 * Closing the handle releases those resources; closing the transport alone would leave the
 * underlying process running.
 */
interface McpTransportHandle : AutoCloseable {
    val transport: Transport
}
