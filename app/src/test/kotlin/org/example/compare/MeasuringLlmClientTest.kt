package org.example.compare

import kotlinx.coroutines.runBlocking
import org.example.ragmode.RecordingLlmClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeasuringLlmClientTest {

    @Test
    fun `elapsedMs reflects the injected clock across the delegate call`() {
        // start = 0, end = 5ms → one completeStructured call = 2 clock reads.
        val clock = TestClock(0, 5_000_000)
        val measuring = MeasuringLlmClient(RecordingLlmClient(), clock::next)

        runBlocking { structured(measuring) }

        assertEquals(5, measuring.elapsedMs)
    }

    @Test
    fun `structuredValid is true for a parseable answer payload`() {
        val valid = RecordingLlmClient(
            structuredJson = """{"answer":"grounded","citations":[],"dont_know":false}""",
        )
        val measuring = MeasuringLlmClient(valid) { 0 }

        runBlocking { structured(measuring) }

        assertTrue(measuring.structuredValid)
    }

    @Test
    fun `structuredValid is false when the payload does not parse`() {
        val measuring = MeasuringLlmClient(RecordingLlmClient(structuredJson = "not valid json"), { 0 })

        runBlocking { structured(measuring) }

        assertFalse(measuring.structuredValid)
    }

    @Test
    fun `the delegate result passes through unchanged`() {
        val measuring = MeasuringLlmClient(RecordingLlmClient(structuredJson = """{"answer":"x"}"""), { 0 })

        val result = runBlocking { structured(measuring) }

        assertEquals("""{"answer":"x"}""", result.toolInputJson)
        assertEquals(7, result.inputTokens) // RecordingLlmClient's canned usage
        assertEquals(3, result.outputTokens)
    }

    @Test
    fun `reset clears elapsed and validity between questions`() {
        val clock = TestClock(0, 9_000_000, 0, 0) // first question, then a second (post-reset) call
        val measuring = MeasuringLlmClient(RecordingLlmClient(), clock::next)

        runBlocking { structured(measuring) }
        assertEquals(9, measuring.elapsedMs)
        assertTrue(measuring.structuredValid)

        measuring.reset()
        assertEquals(0, measuring.elapsedMs)
        assertFalse(measuring.structuredValid)

        runBlocking { structured(measuring) } // second question accumulates from zero
        assertEquals(0, measuring.elapsedMs)
    }

    /** Drives one forced-tool-use call with throwaway tool metadata (the decorator ignores it). */
    private suspend fun structured(client: MeasuringLlmClient) =
        client.completeStructured(
            systemPrompt = "sys",
            messages = emptyList(),
            toolName = "t",
            toolDescription = "d",
            inputSchema = kotlinx.serialization.json.buildJsonObject { },
        )
}
