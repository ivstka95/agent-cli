package org.example.mcp.server.config

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the Day-19 `save_to_file` tool writes (a protected, server-side base directory). Localhost
 * default now; a different deploy location later is a config change (env var), not a code change —
 * mirroring [ServerBindConfig].
 *
 * The default `"out"` is RELATIVE: `./gradlew :mcp:runServer` runs with working dir = the `:mcp` project
 * dir, so it resolves to `<repo>/mcp/out` (gitignored as `/mcp/out/`). [baseDir] is always absolute
 * and normalized so the tool's path-traversal guard has a stable root to compare against.
 */
data class ServerOutputConfig(val baseDir: Path) {

    companion object {
        const val DEFAULT_DIR = "out"

        /** Reads the optional `MCP_OUTPUT_DIR` override; falls back to the relative `out` default. */
        fun fromEnv(): ServerOutputConfig {
            val raw = System.getenv("MCP_OUTPUT_DIR")?.takeIf { it.isNotBlank() } ?: DEFAULT_DIR
            return ServerOutputConfig(Paths.get(raw).toAbsolutePath().normalize())
        }
    }
}
