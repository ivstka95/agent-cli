package org.example.compare

import kotlin.test.Test
import kotlin.test.assertEquals

class ComparisonMetricsTest {

    private fun metric(
        elapsedMs: Long,
        structuredValid: Boolean,
        sourcesPresent: Boolean,
        outputTokens: Int = 0,
    ) = QuestionMetric(
        provider = "local",
        difficulty = "simple",
        question = "q",
        answer = "a",
        elapsedMs = elapsedMs,
        inputTokens = 0,
        outputTokens = outputTokens,
        structuredValid = structuredValid,
        sourcesPresent = sourcesPresent,
        dontKnow = false,
    )

    @Test
    fun `summarize averages elapsed and output tokens and tallies stability and grounding`() {
        val metrics = listOf(
            metric(elapsedMs = 100, structuredValid = true, sourcesPresent = true, outputTokens = 30),
            metric(elapsedMs = 200, structuredValid = false, sourcesPresent = true, outputTokens = 60),
            metric(elapsedMs = 300, structuredValid = true, sourcesPresent = false, outputTokens = 90),
        )

        val summary = summarize("local", metrics)

        assertEquals("local", summary.label)
        assertEquals(3, summary.n)
        assertEquals(200, summary.avgElapsedMs) // (100 + 200 + 300) / 3
        assertEquals(2, summary.structuredValidCount)
        assertEquals(2, summary.sourcesPresentCount)
        assertEquals(60, summary.avgOutputTokens) // (30 + 60 + 90) / 3 — the conciseness signal
    }

    @Test
    fun `summarize of an empty list is all zeros without dividing by zero`() {
        val summary = summarize("cloud", emptyList())

        assertEquals(0, summary.n)
        assertEquals(0, summary.avgElapsedMs)
        assertEquals(0, summary.structuredValidCount)
        assertEquals(0, summary.sourcesPresentCount)
        assertEquals(0, summary.avgOutputTokens)
    }
}
