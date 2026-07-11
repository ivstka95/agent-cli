package org.example.compare

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.example.agent.LlmClient
import org.example.agent.LlmResult
import org.example.agent.Message
import org.example.agent.StructuredResult

/**
 * [Day 28] A transparent [LlmClient] decorator that MEASURES a provider without changing the RAG path.
 * It wraps the real client and, per question, records:
 *
 *  - [elapsedMs] — wall-clock spent inside the delegate's generation call(s). Timing lives here (not in
 *    [org.example.ragmode.RagResponder]) so we measure ONLY generation — retrieval is local and identical
 *    for both providers, so excluding it isolates the true local-vs-cloud speed difference. The clock is
 *    injected ([nanoTime]) so tests are deterministic without touching real time.
 *  - [structuredValid] — whether the structured-output JSON actually parsed. `RagResponder` swallows a
 *    parse failure internally (it surfaces the raw payload as the answer and never flags it), so this is
 *    the only place to observe it. True iff at least one `completeStructured` call this question parsed —
 *    matching the responder, which uses the first parseable result and only falls back when NONE parse.
 *
 * Every method delegates verbatim, so the wrapped `RagResponder` behaves exactly as if unwrapped. It is
 * deliberately NOT [AutoCloseable]: the underlying provider client is owned/closed elsewhere.
 */
class MeasuringLlmClient(
    private val delegate: LlmClient,
    private val nanoTime: () -> Long = System::nanoTime,
) : LlmClient {

    private var elapsedNanos: Long = 0
    private var anyStructuredParsed: Boolean = false

    /** Generation wall-clock accumulated since the last [reset], in milliseconds. */
    val elapsedMs: Long get() = elapsedNanos / 1_000_000

    /** Whether any structured call since the last [reset] produced valid `{answer, …}` JSON. */
    val structuredValid: Boolean get() = anyStructuredParsed

    /** Clear the per-question metrics; call before each question. */
    fun reset() {
        elapsedNanos = 0
        anyStructuredParsed = false
    }

    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult =
        timed { delegate.complete(systemPrompt, messages) }

    override suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult {
        val result = timed {
            delegate.completeStructured(systemPrompt, messages, toolName, toolDescription, inputSchema)
        }
        if (parses(result.toolInputJson)) anyStructuredParsed = true
        return result
    }

    private inline fun <T> timed(block: () -> T): T {
        val start = nanoTime()
        try {
            return block()
        } finally {
            elapsedNanos += nanoTime() - start
        }
    }

    /**
     * "Valid" here means exactly what `RagResponder` means — a payload it would NOT fall back on. So
     * [CompareStructured] mirrors `RagResponder.RagStructuredOutput` field-for-field (keep the two in sync):
     * decoding must fail on the same inputs the responder rejects, e.g. a good `answer` but a malformed
     * `citations` entry. Otherwise this would report `structured ✓` on a payload the responder discarded.
     */
    private fun parses(toolInputJson: String): Boolean =
        runCatching { JSON.decodeFromString<CompareStructured>(toolInputJson) }.isSuccess

    @Serializable
    private data class CompareStructured(
        val answer: String,
        val citations: List<CompareCitation> = emptyList(),
        @SerialName("dont_know") val dontKnow: Boolean = false,
    )

    @Serializable
    private data class CompareCitation(val quote: String, val source: String)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
