package org.example.digest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Specs for the pure delta + stats aggregation. Given a previous state and the commits collected
 * this tick, When applied, Then only unseen SHAs count as new and the counters accumulate.
 */
class DigestAggregatorTest {

    private val aggregator = DigestAggregator()

    private fun commit(sha: String, author: String = "Ada") =
        CollectedCommit(sha, "msg $sha", author, "2026-06-24T10:00:00Z")

    @Test
    fun `first tick treats all collected commits as new`() {
        val result = aggregator.apply(DigestState(), listOf(commit("a1"), commit("b2")))

        assertEquals(2, result.newCommits.size)
        assertEquals(setOf("a1", "b2"), result.state.seenShas)
        assertEquals(2, result.state.totalTracked)
        assertEquals(1, result.state.ticks)
        assertTrue(result.summary.contains("NEW (2)"), result.summary)
    }

    @Test
    fun `second tick reports only the unseen commits and accumulates counters`() {
        val first = aggregator.apply(DigestState(), listOf(commit("a1")))
        val second = aggregator.apply(first.state, listOf(commit("a1"), commit("b2")))

        assertEquals(listOf("b2"), second.newCommits.map { it.sha })
        assertEquals(setOf("a1", "b2"), second.state.seenShas)
        assertEquals(2, second.state.totalTracked)
        assertEquals(2, second.state.ticks)
    }

    @Test
    fun `no new commits yields a no-changes summary but still advances the tick counter`() {
        val seeded = DigestState(seenShas = setOf("a1"), ticks = 1)

        val result = aggregator.apply(seeded, listOf(commit("a1")))

        assertTrue(result.newCommits.isEmpty())
        assertTrue(result.summary.contains("No changes"), result.summary)
        assertEquals(1, result.state.totalTracked)
        assertEquals(2, result.state.ticks)
    }

    @Test
    fun `most active author counts across ticks and breaks ties alphabetically`() {
        val s1 = aggregator.apply(DigestState(), listOf(commit("a1", "Ada"), commit("b2", "Ada")))
        val s2 = aggregator.apply(s1.state, listOf(commit("c3", "Bo")))

        assertEquals("Ada", s2.state.mostActiveAuthor)
        assertTrue(s2.summary.contains("Most active: Ada (2)"), s2.summary)
    }
}
