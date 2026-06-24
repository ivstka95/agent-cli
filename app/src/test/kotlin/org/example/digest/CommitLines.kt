package org.example.digest

/**
 * Test helper: render commits exactly as the Day-17 `get_recent_commits` tool does, so collector
 * and scheduler tests parse the real on-the-wire format (`- {sha7} {message} — {author} ({date})`).
 */
fun toolOutput(owner: String, repo: String, commits: List<CollectedCommit>): String = buildString {
    appendLine("Recent commits for $owner/$repo (${commits.size}):")
    commits.forEach { c ->
        appendLine("- ${c.sha} ${c.message} — ${c.author} (${c.date})")
    }
}.trimEnd()
