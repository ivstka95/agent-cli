package org.example.digest

/**
 * One commit as collected from the `get_recent_commits` tool output. App-side and intentionally
 * separate from `:mcp`'s `Commit` so the digest layer stays decoupled from the MCP module.
 *
 * [sha] is the short SHA that leads each tool-output line — the stable identity the delta keys on.
 */
data class CollectedCommit(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,
)
