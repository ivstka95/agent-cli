package org.example.memory

import org.example.agent.Message

/**
 * Short-term memory (Day 11): the current session's dialog.
 *
 * This is the in-memory conversation history the REPL accumulates within a run.
 * It is NOT persisted — it lives only for the lifetime of the process and is
 * replayed into the `messages` array on every LLM call (the model is stateless).
 *
 * Formalized as its own type so the three memory layers are explicit and
 * symmetrical, even though this one is the thinnest: it wraps a mutable list.
 */
class ShortTermMemory {

    private val messages = mutableListOf<Message>()

    /** Append a turn to the session history. */
    fun add(message: Message) {
        messages += message
    }

    /** A snapshot of the session history, in order. Goes into the `messages` array. */
    fun history(): List<Message> = messages.toList()

    /** Drop all session history (e.g. on an explicit reset). */
    fun clear() {
        messages.clear()
    }
}
