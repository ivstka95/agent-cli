package org.example.mcp.server

import org.example.mcp.server.config.ServerBindConfig
import org.example.mcp.server.github.GitHubClient
import org.example.mcp.server.transport.HttpServerTransportFactory

/**
 * Primary `:mcp` entry point (Day 17): starts our GitHub MCP server over HTTP and blocks.
 *
 * Run: `./gradlew :mcp:run` (then run the agent in another terminal). Override the bind address with
 * `MCP_HOST` / `MCP_PORT` env vars — a VPS deploy is a config change, not a code change.
 */
fun main() {
    val bind = ServerBindConfig.fromEnv()
    val github = GitHubClient()
    val handle = HttpServerTransportFactory(bind, github).start()

    println("GitHub MCP server listening on ${handle.address}")
    println("Tool: get_recent_commits(owner, repo, limit?) — public GitHub API, no token.")
    println("Press Ctrl-C to stop.")

    handle.awaitShutdown()
}
