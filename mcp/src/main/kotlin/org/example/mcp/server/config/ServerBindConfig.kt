package org.example.mcp.server.config

/**
 * Where the HTTP MCP server binds. Localhost now (rehearses deployment); a VPS deploy later is a
 * config change (env vars), not a code change.
 */
data class ServerBindConfig(val host: String = DEFAULT_HOST, val port: Int = DEFAULT_PORT) {
    /** Base URL the SSE client connects to. */
    val url: String get() = "http://$host:$port"

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 3001

        /** Reads optional `MCP_HOST` / `MCP_PORT` overrides; falls back to localhost defaults. */
        fun fromEnv(): ServerBindConfig = ServerBindConfig(
            host = System.getenv("MCP_HOST")?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST,
            port = System.getenv("MCP_PORT")?.toIntOrNull() ?: DEFAULT_PORT,
        )
    }
}
