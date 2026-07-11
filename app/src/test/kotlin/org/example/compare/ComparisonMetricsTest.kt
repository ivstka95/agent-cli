package org.example.compare

import kotlin.test.Test
import kotlin.test.assertEquals

class ComparisonMetricsTest {

    private fun metric(
        elapsedMs: Long,
        structuredValid: Boolean,
        sourcesPresent: Boolean,
    ) = QuestionMetric(
        provider = "local",
        difficulty = "simple",
        question = "q",
        answer = "a",
        elapsedMs = elapsedMs,
        inputTokens = 0,
        outputTokens = 0,
        structuredValid = structuredValid,
        sourcesPresent = sourcesPresent,
        dontKnow = false,
    )

    @Test
    fun `summarize averages elapsed and tallies stability and grounding`() {
        val metrics = listOf(
            metric(elapsedMs = 100, structuredValid = true, sourcesPresent = true),
            metric(elapsedMs = 200, structuredValid = false, sourcesPresent = true),
            metric(elapsedMs = 300, structuredValid = true, sourcesPresent = false),
        )

        val summary = summarize("local", metrics)

        assertEquals("local", summary.label)
        assertEquals(3, summary.n)
        assertEquals(200, summary.avgElapsedMs) // (100 + 200 + 300) / 3
        assertEquals(2, summary.structuredValidCount)
        assertEquals(2, summary.sourcesPresentCount)
    }

    @Test
    fun `summarize of an empty list is all zeros without dividing by zero`() {
        val summary = summarize("cloud", emptyList())

        assertEquals(0, summary.n)
        assertEquals(0, summary.avgElapsedMs)
        assertEquals(0, summary.structuredValidCount)
        assertEquals(0, summary.sourcesPresentCount)
    }
}
