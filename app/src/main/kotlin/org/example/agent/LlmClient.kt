package org.example.agent

/**
 * Abstraction over an LLM provider. The Agent depends ONLY on this interface,
 * never on a concrete client — keeps the domain free of transport details.
 */
interface LlmClient {
    suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult
}
