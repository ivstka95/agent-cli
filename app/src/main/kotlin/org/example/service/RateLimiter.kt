package org.example.service

import java.util.concurrent.ConcurrentHashMap

/**
 * [Day 30] Basic in-memory rate limit (the Day-30 task requirement) — a fixed 60s window per key
 * (the client IP). At most [limitPerMin] calls per key per window; the [limitPerMin + 1]-th in the same
 * window is rejected. When the window elapses the count resets. No cleanup/eviction — fine for a small
 * private service (the key set is bounded by distinct client IPs); a restart clears it.
 *
 * [now] is injectable so the window logic is unit-testable offline without real time; production uses
 * `System::currentTimeMillis`. Thread-safe: [tryAcquire] mutates each key's counter under `compute`,
 * which `ConcurrentHashMap` runs atomically per key, so concurrent requests can't lose an increment.
 */
class RateLimiter(
    private val limitPerMin: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Window(val startMs: Long, val count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    /** Returns true if this call is allowed (and counts it); false if [key] is over the limit now. */
    fun tryAcquire(key: String): Boolean {
        if (limitPerMin <= 0) return true // a non-positive limit disables rate limiting
        val nowMs = now()
        var allowed = false
        windows.compute(key) { _, current ->
            val fresh = current == null || nowMs - current.startMs >= WINDOW_MS
            if (fresh) {
                allowed = true
                Window(startMs = nowMs, count = 1)
            } else if (current.count < limitPerMin) {
                allowed = true
                current.copy(count = current.count + 1)
            } else {
                allowed = false
                current // over the limit — keep the window unchanged
            }
        }
        return allowed
    }

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}
