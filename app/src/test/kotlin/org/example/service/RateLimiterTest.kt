package org.example.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Day 30] Specs for the fixed-window rate limiter — offline, with an injected clock so the window
 * boundary is exercised without real time.
 */
class RateLimiterTest {

    @Test
    fun `allows up to the limit then rejects within the same window`() {
        var now = 1_000L
        val limiter = RateLimiter(limitPerMin = 3, now = { now })

        assertTrue(limiter.tryAcquire("ip1"))
        assertTrue(limiter.tryAcquire("ip1"))
        assertTrue(limiter.tryAcquire("ip1"))
        assertFalse(limiter.tryAcquire("ip1")) // 4th in the window → rejected
    }

    @Test
    fun `resets once the window elapses`() {
        var now = 0L
        val limiter = RateLimiter(limitPerMin = 1, now = { now })

        assertTrue(limiter.tryAcquire("ip1"))
        assertFalse(limiter.tryAcquire("ip1"))

        now += 60_000L // advance past the 60s window
        assertTrue(limiter.tryAcquire("ip1"))
    }

    @Test
    fun `keys are limited independently`() {
        var now = 0L
        val limiter = RateLimiter(limitPerMin = 1, now = { now })

        assertTrue(limiter.tryAcquire("ipA"))
        assertFalse(limiter.tryAcquire("ipA"))
        assertTrue(limiter.tryAcquire("ipB")) // a different key has its own window
    }

    @Test
    fun `a non-positive limit disables limiting`() {
        val limiter = RateLimiter(limitPerMin = 0)
        repeat(100) { assertTrue(limiter.tryAcquire("ip1")) }
    }
}
