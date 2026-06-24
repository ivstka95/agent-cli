package org.example.agent

/**
 * Minimal ANSI color helper for the agent's stdout logging, so MCP tool-call lines stand out in the
 * :app:run terminal. Agent side uses green. Kept tiny and module-local on purpose — no cross-module
 * coupling just for colors (the server has its own copy in cyan).
 *
 * ESC is built from its code point (27) so the source stays free of invisible control bytes.
 */
internal object Ansi {
    private val ESC: String = 27.toChar().toString()
    private val RESET = ESC + "[0m"
    private val BOLD = ESC + "[1m"
    private val GREEN = ESC + "[32m"

    fun green(s: String): String = GREEN + s + RESET
    fun bold(s: String): String = BOLD + s + RESET
}
