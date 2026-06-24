package org.example.digest

import kotlinx.serialization.Serializable

/**
 * Persisted background-digest state: the set of commit SHAs seen so far (to compute the delta) plus
 * session counters. Serialized to a JSON file by [DigestStore], kept SEPARATE from the agent's
 * `memory/` (different data: digest state, not conversational memory).
 *
 * Counters are cumulative across runs because the state is reloaded on restart — that is exactly
 * what makes already-seen commits stop being reported as new.
 */
@Serializable
data class DigestState(
    val seenShas: Set<String> = emptySet(),
    val authorCounts: Map<String, Int> = emptyMap(),
    val ticks: Int = 0,
) {
    /** Total distinct commits tracked — always exactly [seenShas]`.size`, so it is derived, not stored. */
    val totalTracked: Int get() = seenShas.size

    /**
     * The author with the most commits tracked, or `null` if none yet. Ties broken alphabetically
     * so the value is deterministic (and therefore testable), regardless of map iteration order.
     */
    val mostActiveAuthor: String?
        get() = authorCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key
}
