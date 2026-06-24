package org.example.digest

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * The Day-18 scheduler: the coroutine loop that makes the agent "run 24/7". Each tick it collects
 * recent commits (via the existing MCP tool), aggregates the delta + stats against the persisted
 * state, saves, and emits the summary. [run] loops until the coroutine is cancelled (Ctrl-C),
 * delaying [intervalMillis] between ticks; [tick] is exposed separately so the per-tick logic is
 * testable off the timing loop.
 *
 * Persisted state is the source of truth — it is reloaded every tick, so a restart resumes from the
 * last-seen commits with no spurious "new" entries.
 */
class DigestScheduler(
    private val owner: String,
    private val repo: String,
    private val limit: Int?,
    private val intervalMillis: Long,
    private val collector: CommitCollector,
    private val store: DigestStore,
    private val aggregator: DigestAggregator = DigestAggregator(),
    private val emit: (String) -> Unit = ::println,
) {

    /** One collect → aggregate → persist → emit cycle. Returns the rendered summary. */
    suspend fun tick(): String {
        val previous = store.load()
        val collected = collector.collect(owner, repo, limit)
        val result = aggregator.apply(previous, collected)
        store.save(result.state)
        emit(result.summary)
        return result.summary
    }

    /** Tick, then wait [intervalMillis], repeating until the coroutine is cancelled. */
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            tick()
            delay(intervalMillis)
        }
    }
}
