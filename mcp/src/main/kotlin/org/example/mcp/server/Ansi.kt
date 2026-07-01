package org.example.mcp.server

/**
 * Minimal ANSI color helper for the server's stdout logging, so MCP tool-call lines stand out in the
 * :mcp:runServer terminal. Server side uses cyan. Kept tiny and module-local on purpose — no cross-module
 * coupling just for colors (the agent has its own copy in green).
 *
 * ESC is built from its code point (27) so the source stays free of invisible control bytes.
 */
internal object Ansi {
    private val ESC: String = 27.toChar().toString()
    private val RESET = ESC + "[0m"
    private val BOLD = ESC + "[1m"
    private val CYAN = ESC + "[36m"

    fun cyan(s: String): String = CYAN + s + RESET
    fun bold(s: String): String = BOLD + s + RESET
}
