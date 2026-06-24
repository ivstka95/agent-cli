package org.example.mcp.server.github

import kotlinx.serialization.Serializable

/**
 * Minimal model of one commit, projected from the GitHub REST response.
 * [message] is the first line of the commit message (the subject).
 */
data class Commit(val message: String, val author: String, val date: String)

/** Raised when the GitHub API returns a non-success status (e.g. 404, 403 rate-limit). */
class GitHubApiException(val statusCode: Int, val body: String) :
    RuntimeException("GitHub API error $statusCode") {
    /** A short, single-line excerpt of the error body for surfacing to the model. */
    fun shortBody(): String = body.replace(Regex("\\s+"), " ").trim().take(200)
}

// ── GitHub REST DTOs (only the fields we use) ───────────────────────────────────
// Endpoint: GET /repos/{owner}/{repo}/commits — see
// https://docs.github.com/en/rest/commits/commits#list-commits

@Serializable
internal data class CommitDto(
    val sha: String = "",
    val commit: CommitDetailDto = CommitDetailDto(),
) {
    fun toCommit(): Commit = Commit(
        message = commit.message.lineSequence().firstOrNull()?.trim().orEmpty(),
        author = commit.author.name,
        date = commit.author.date,
    )
}

@Serializable
internal data class CommitDetailDto(
    val message: String = "",
    val author: CommitAuthorDto = CommitAuthorDto(),
)

@Serializable
internal data class CommitAuthorDto(
    val name: String = "",
    val date: String = "",
)
