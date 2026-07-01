package org.example.digest

import kotlinx.coroutines.runBlocking
import org.example.mcp.McpClient
import org.example.mcp.SdkMcpClient
import org.example.mcp.transport.HttpClientTransportFactory
import kotlin.system.exitProcess

/**
 * [Day 18] Background digest entry point — a SEPARATE run mode from the interactive REPL
 * (`org.example.MainKt`), mirroring how `:mcp` separates its server and client-demo entry points.
 *
 * It connects to the GitHub MCP server (reusing the Day-17 plumbing), then runs the scheduler loop:
 * every interval it calls `get_recent_commits`, computes the delta of new commits vs. the persisted
 * last-seen set, and prints an aggregated summary — an agent that "runs 24/7 and periodically emits
 * a summary." Stop with Ctrl-C.
 *
 * Config (env, all optional):
 *   MCP_SERVER_URL (default http://127.0.0.1:3001), DIGEST_OWNER, DIGEST_REPO,
 *   DIGEST_LIMIT (server default if unset), DIGEST_INTERVAL_SECONDS (default 60).
 */
fun main() = runBlocking {
    val serverUrl = System.getenv("MCP_SERVER_URL")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:3001"
    val owner = System.getenv("DIGEST_OWNER")?.takeIf { it.isNotBlank() } ?: "ivstka95"
    val repo = System.getenv("DIGEST_REPO")?.takeIf { it.isNotBlank() } ?: "agent-cli"
    val limit = System.getenv("DIGEST_LIMIT")?.toIntOrNull()
    val intervalSeconds = System.getenv("DIGEST_INTERVAL_SECONDS")?.toLongOrNull()?.coerceAtLeast(1) ?: 60L

    val mcpClient: McpClient = SdkMcpClient(HttpClientTransportFactory(serverUrl))
    try {
        mcpClient.connect()
    } catch (e: Exception) {
        System.err.println(
            "Could not reach MCP server at $serverUrl (${e.message}). " +
                "Start it first with `./gradlew :mcp:runServer`.",
        )
        exitProcess(1)
    }

    println("Digest mode: watching $owner/$repo every ${intervalSeconds}s via $serverUrl. Ctrl-C to stop.")

    val scheduler = DigestScheduler(
        owner = owner,
        repo = repo,
        limit = limit,
        intervalMillis = intervalSeconds * 1000,
        collector = CommitCollector(mcpClient),
        store = DigestStore(),
    )
    try {
        scheduler.run()
    } finally {
        mcpClient.close()
    }
}
