package org.example.mcp.server

/**
 * One shared `[MCP SERVER]` logging point (Day 19). Every tool routes its `tool call:` / `result:`
 * transparency lines through here so they look identical in the `:mcp:runServer` terminal — cyan, bold
 * prefix. Extracted from `GetRecentCommitsTool` (Day 17) when a second and third tool arrived, so the
 * color/format lives in ONE place instead of being copy-pasted per tool.
 */
internal object McpServerLog {
    /** One cyan, bold-prefixed `[MCP SERVER]` line to stdout. */
    fun line(body: String) =
        println(Ansi.bold(Ansi.cyan("[MCP SERVER]")) + " " + Ansi.cyan(body))
}
