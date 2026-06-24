package org.example.digest

/** The outcome of one digest tick: state to persist, the new commits, and the rendered summary. */
data class TickResult(
    val state: DigestState,
    val newCommits: List<CollectedCommit>,
    val summary: String,
)

/**
 * Pure aggregation — the heart of the digest. Given the previous [DigestState] and the commits
 * collected this tick, it computes the delta (commits whose SHA wasn't seen before), updates the
 * counters, and renders the aggregated summary (delta + brief stats). No IO, no clock → fully
 * unit-testable with plain values.
 */
class DigestAggregator {

    fun apply(previous: DigestState, collected: List<CollectedCommit>): TickResult {
        val newCommits = collected.filter { it.sha !in previous.seenShas }

        val authorCounts = previous.authorCounts.toMutableMap()
        newCommits.forEach { authorCounts.merge(it.author, 1, Int::plus) }

        val state = previous.copy(
            seenShas = previous.seenShas + newCommits.map { it.sha },
            authorCounts = authorCounts,
            ticks = previous.ticks + 1,
        )
        return TickResult(state, newCommits, render(state, newCommits))
    }

    private fun render(state: DigestState, newCommits: List<CollectedCommit>): String = buildString {
        appendLine("── Digest tick #${state.ticks} ──")
        if (newCommits.isEmpty()) {
            appendLine("No changes since last check.")
        } else {
            appendLine("NEW (${newCommits.size}):")
            newCommits.forEach { c ->
                appendLine("  - ${c.sha} ${c.message} — ${c.author} (${c.date})")
            }
        }
        append("Total tracked: ${state.totalTracked}")
        state.mostActiveAuthor?.let { append(" | Most active: $it (${state.authorCounts[it]})") }
    }
}
