package org.example.mcp.config

/**
 * Configurable launch command for the MCP server subprocess.
 *
 * The command is the ONLY place a server is chosen. Switching to `server-filesystem`,
 * `server-fetch`, or our own server (later days) is a config change here, not a code change in
 * the client or transport.
 */
data class ServerConfig(val command: List<String>) {
    companion object {
        /** The Day 16 demo server: `@modelcontextprotocol/server-everything` over stdio via npx. */
        fun serverEverything(): ServerConfig =
            ServerConfig(listOf("npx", "-y", "@modelcontextprotocol/server-everything"))

        /**
         * Day 20: the third-party filesystem server, sandboxed to [allowedDir], over stdio via npx.
         * The server confines every read/write to [allowedDir] (which must exist when it launches),
         * so its `write_file`/`read_file` tools land somewhere inspectable on disk.
         */
        fun serverFilesystem(allowedDir: String): ServerConfig =
            ServerConfig(listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", allowedDir))
    }
}
