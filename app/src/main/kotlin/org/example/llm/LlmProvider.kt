package org.example.llm

import kotlinx.serialization.json.JsonObject
import org.example.agent.LlmClient
import org.example.agent.LlmResult
import org.example.agent.LlmTurn
import org.example.agent.Message
import org.example.agent.StructuredResult
import org.example.agent.ToolExchange
import org.example.agent.ToolSpec

/**
 * [Day 27] Which generative backend answers: [CLOUD] (Anthropic) or [LOCAL] (Ollama). The [label] is
 * also the `:llm` command argument and the per-answer tag the REPL prints (`[local]` / `[cloud]`).
 */
enum class Provider(val label: String) {
    LOCAL("local"),
    CLOUD("cloud"),
    ;

    companion object {
        fun parse(value: String): Provider? = when (value.trim().lowercase()) {
            "local", "ollama" -> LOCAL
            "cloud", "anthropic" -> CLOUD
            else -> null
        }
    }
}

/**
 * [Day 27] Builds a concrete [LlmClient] for a [Provider]. Used directly by the non-interactive eval
 * mains (which honor `LLM_PROVIDER` but don't switch live) and as the per-provider builder for
 * [LlmProviderSwitch].
 */
object LlmClientFactory {
    /** Construct the client for [provider]. CLOUD reads `ANTHROPIC_API_KEY` and throws if it's missing. */
    fun build(provider: Provider): LlmClient = when (provider) {
        Provider.CLOUD -> AnthropicClient()
        // No network at construction → never throws; the app runs fully offline on LOCAL.
        Provider.LOCAL -> OllamaLlmClient(LlmConfig.fromEnv())
    }

    /** The initial provider from `LLM_PROVIDER` (anthropic | ollama); defaults to CLOUD. */
    fun provider(): Provider = System.getenv("LLM_PROVIDER")?.let { Provider.parse(it) } ?: Provider.CLOUD

    fun fromEnv(): LlmClient = build(provider())
}

/**
 * [Day 27] A delegating [LlmClient] whose backend can be swapped at runtime. Injected ONCE into the whole
 * graph (generator, agentic loop, RAG rewriter, RagResponder); flipping [active] switches every path live
 * without rebuilding anything. Delegates all three methods — including [runToolTurn], so when [active] is
 * a tool-less local client the interface's throwing default propagates (and `AgenticLoop` degrades).
 */
class SwitchableLlmClient(initial: LlmClient) : LlmClient {

    @Volatile
    var active: LlmClient = initial

    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult =
        active.complete(systemPrompt, messages)

    override suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult =
        active.completeStructured(systemPrompt, messages, toolName, toolDescription, inputSchema)

    override suspend fun runToolTurn(
        systemPrompt: String,
        messages: List<Message>,
        exchanges: List<ToolExchange>,
        tools: List<ToolSpec>,
    ): LlmTurn =
        active.runToolTurn(systemPrompt, messages, exchanges, tools)
}

/** Outcome of a `:llm` switch — drives the REPL's confirmation message. */
sealed interface SwitchResult {
    /** The active provider changed to [to]. */
    data class Switched(val to: Provider) : SwitchResult

    /** [to] was already active — nothing changed. */
    data class Unchanged(val to: Provider) : SwitchResult

    /** Building [to] failed (e.g. missing API key); the current provider is kept. [reason] explains why. */
    data class Failed(val to: Provider, val reason: String) : SwitchResult
}

/**
 * [Day 27] Owns the live provider toggle: the injected [client] decorator plus LAZY per-provider
 * construction. Only the INITIAL provider is built eagerly (so `LLM_PROVIDER=ollama` starts fully offline
 * with no API key); the other is built on first [switchTo] and memoized. A failed build (e.g. CLOUD with
 * no key) is NOT cached and leaves the current provider untouched — the REPL reports it and keeps running.
 *
 * Constructed via [fromEnv]; the [builders] constructor is `internal` so tests can inject fakes.
 */
class LlmProviderSwitch internal constructor(
    initial: Provider,
    private val builder: (Provider) -> LlmClient,
) : AutoCloseable {

    private val instances = mutableMapOf<Provider, LlmClient>()

    var current: Provider = initial
        private set

    /** The single decorator injected everywhere as the app's [LlmClient]. */
    val client: SwitchableLlmClient = SwitchableLlmClient(getOrBuild(initial))

    /** Switch the active provider, building the target lazily on first use. */
    fun switchTo(target: Provider): SwitchResult {
        if (target == current) return SwitchResult.Unchanged(target)
        val next = try {
            getOrBuild(target)
        } catch (e: IllegalStateException) {
            // e.g. CLOUD selected but ANTHROPIC_API_KEY is missing — stay put, don't crash.
            return SwitchResult.Failed(target, e.message.orEmpty())
        }
        client.active = next
        current = target
        return SwitchResult.Switched(target)
    }

    private fun getOrBuild(provider: Provider): LlmClient =
        instances.getOrPut(provider) { builder(provider) }

    /** Close any built providers that hold resources (the Ollama HTTP client). */
    override fun close() = instances.values.filterIsInstance<AutoCloseable>().forEach { it.close() }

    companion object {
        fun fromEnv(): LlmProviderSwitch =
            LlmProviderSwitch(LlmClientFactory.provider(), LlmClientFactory::build)
    }
}
