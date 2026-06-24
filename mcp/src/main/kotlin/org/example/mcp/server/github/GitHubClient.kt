package org.example.mcp.server.github

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Thin wrapper over the **public** GitHub REST API. No token: the unauthenticated
 * `GET /repos/{owner}/{repo}/commits` endpoint allows 60 requests/hour.
 *
 * The HTTP engine is injectable so tests can stub GitHub with a Ktor `MockEngine`; production uses
 * the CIO engine. [baseUrl] is overridable for the same reason (defaults to api.github.com).
 */
class GitHubClient(
    engine: HttpClientEngine? = null,
    private val baseUrl: String = "https://api.github.com",
) : AutoCloseable {

    private val http: HttpClient =
        if (engine != null) {
            HttpClient(engine) { install(ContentNegotiation) { json(JSON) } }
        } else {
            HttpClient(CIO) { install(ContentNegotiation) { json(JSON) } }
        }

    /**
     * Returns up to [limit] most-recent commits for `owner/repo`, newest first.
     *
     * @throws GitHubApiException on a non-success HTTP status (e.g. 404 unknown repo, 403 rate-limit).
     */
    suspend fun recentCommits(owner: String, repo: String, limit: Int): List<Commit> {
        val response = http.get("$baseUrl/repos/$owner/$repo/commits?per_page=$limit") {
            headers {
                // GitHub requires a User-Agent on every request.
                append(HttpHeaders.UserAgent, USER_AGENT)
                append(HttpHeaders.Accept, "application/vnd.github+json")
            }
        }
        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            throw GitHubApiException(response.status.value, body)
        }
        return response.body<List<CommitDto>>().map { it.toCommit() }
    }

    override fun close() {
        runCatching { http.close() }
    }

    private companion object {
        const val USER_AGENT = "agent-cli-mcp"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
