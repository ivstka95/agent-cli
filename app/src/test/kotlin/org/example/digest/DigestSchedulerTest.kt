package org.example.digest

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.success
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Specs for the scheduler. tick() drives collect→aggregate→persist→emit; run() loops until cancelled.
 */
class DigestSchedulerTest {

    private fun commit(sha: String, author: String = "Ada") =
        CollectedCommit(sha, "msg $sha", author, "2026-06-24T10:00:00Z")

    private fun tempStateFile() = File.createTempFile("digest-sched", ".json").apply { delete() }

    @Test
    fun `consecutive ticks report only new commits and persist the growing seen-set`() = runBlocking {
        val file = tempStateFile()
        try {
            val perTick = ArrayDeque(
                listOf(
                    listOf(commit("a1b2c3d")),
                    listOf(commit("a1b2c3d"), commit("e4f5a6b")),
                ),
            )
            val client = FakeMcpClient { _, _ ->
                CallToolResult.success(toolOutput("o", "r", perTick.removeFirst()))
            }
            val store = DigestStore(file)
            val scheduler = DigestScheduler("o", "r", null, 10_000, CommitCollector(client), store, emit = {})

            val first = scheduler.tick()
            val second = scheduler.tick()

            assertTrue(first.contains("NEW (1)"), first)
            assertTrue(second.contains("NEW (1)") && second.contains("e4f5a6b"), second)
            assertTrue(!second.contains("a1b2c3d"), "an already-seen commit must not reappear: $second")

            val persisted = store.load()
            assertEquals(setOf("a1b2c3d", "e4f5a6b"), persisted.seenShas)
            assertEquals(2, persisted.totalTracked)
            assertEquals(2, persisted.ticks)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a restart reloads the persisted state so seen commits are not re-reported`() = runBlocking {
        val file = tempStateFile()
        try {
            val client = FakeMcpClient { _, _ ->
                CallToolResult.success(toolOutput("o", "r", listOf(commit("a1b2c3d"))))
            }
            // First "process": one tick records a1.
            DigestScheduler("o", "r", null, 10_000, CommitCollector(client), DigestStore(file), emit = {}).tick()

            // Second "process": fresh scheduler + store over the SAME file, same commit returned.
            val summary = DigestScheduler(
                "o", "r", null, 10_000, CommitCollector(client), DigestStore(file), emit = {},
            ).tick()

            assertTrue(summary.contains("No changes"), "a1 was already seen before restart: $summary")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `run keeps ticking until cancelled and then stops cleanly`() = runBlocking {
        val file = tempStateFile()
        try {
            val firstEmit = CompletableDeferred<Unit>()
            var emits = 0
            val client = FakeMcpClient { _, _ ->
                CallToolResult.success(toolOutput("o", "r", listOf(commit("a1b2c3d"))))
            }
            val scheduler = DigestScheduler(
                "o", "r", null, 10_000, CommitCollector(client), DigestStore(file),
                emit = { emits++; if (!firstEmit.isCompleted) firstEmit.complete(Unit) },
            )

            val job = launch { scheduler.run() }
            firstEmit.await() // a tick happened; the loop is now parked in delay()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertTrue(emits >= 1, "expected at least one tick before cancellation")
        } finally {
            file.delete()
        }
    }
}
