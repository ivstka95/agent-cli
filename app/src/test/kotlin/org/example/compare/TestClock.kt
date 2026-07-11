package org.example.compare

/**
 * A scripted nanosecond clock for the measuring-decorator tests: each [next] returns the next tick, so
 * elapsed timings are deterministic without touching real time. [MeasuringLlmClient] reads the clock twice
 * per delegate call (start, end), so supply ticks in start/end pairs.
 */
internal class TestClock(vararg ticks: Long) {
    private val remaining = ArrayDeque(ticks.toList())
    fun next(): Long = remaining.removeFirst()
}
