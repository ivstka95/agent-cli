package org.example.mcp.server.transport

/**
 * Server-side counterpart of the client's transport factory: the single seam that hides HOW the
 * server is exposed (HTTP/SSE now, stdio fallback). Swapping transports — or, later, pointing the
 * HTTP server at a VPS address — is a one-place change.
 */
interface McpServerTransportFactory {
    /** Starts serving (non-blocking) and returns a running handle. */
    fun start(): McpServerHandle
}

/** A running server transport. [close] stops it; [awaitShutdown] blocks until the process is killed. */
interface McpServerHandle : AutoCloseable {
    /** Human-readable address the server is reachable at (for logging). */
    val address: String

    /** Blocks the calling thread until shutdown — used by the standalone server `main`. */
    fun awaitShutdown()
}
