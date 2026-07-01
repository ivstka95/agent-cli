package org.example.mcp.server

import org.example.mcp.server.config.ServerBindConfig
import org.example.mcp.server.config.ServerOutputConfig
import org.example.mcp.server.github.GitHubClient
import org.example.mcp.server.transport.HttpServerTransportFactory

/**
 * Primary `:mcp` entry point (Day 17): starts our GitHub MCP server over HTTP and blocks.
 *
 * Run: `./gradlew :mcp:runServer` (then run the agent in another terminal). Override the bind address with
 * `MCP_HOST` / `MCP_PORT` and the file-output dir with `MCP_OUTPUT_DIR` — a VPS deploy is a config
 * change, not a code change.
 */
fun main() {
    val bind = ServerBindConfig.fromEnv()
    val output = ServerOutputConfig.fromEnv()
    val github = GitHubClient()
    val handle = HttpServerTransportFactory(bind, github, output.baseDir).start()

    println("GitHub MCP server listening on ${handle.address}")
    println("Tools (Day 19 pipeline):")
    println("  - get_recent_commits(owner, repo, limit?) — public GitHub API, no token.")
    println("  - build_commit_report(commits) — deterministic report (no LLM).")
    println("  - save_to_file(filename, content) — writes under ${output.baseDir}")
    println("Press Ctrl-C to stop.")

    handle.awaitShutdown()
}
