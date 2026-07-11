package org.example.service

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.example.agent.Message

/**
 * [Day 30] In-memory per-session chat history — `Map<sessionId, history>`, no DB, no files; a restart
 * clears everything. Each browser gets its own [newId] (UUID) and its history lives while the server
 * runs. Sessions are ISOLATED: [snapshot] for one id never sees another's messages, so concurrent users
 * don't mix.
 *
 * Thread-safe: the map is a [ConcurrentHashMap] and each session's list is created ATOMICALLY via
 * `computeIfAbsent` (the stdlib `getOrPut` is a racy get-then-put), then every read/mutation of that list
 * is done under its monitor (`synchronized`), so concurrent requests to the SAME session can't corrupt it
 * or race the trim. [maxHistory] caps retained messages (the "max context" limit) — after an append the
 * list is trimmed to the last [maxHistory] entries before the next model call; it is coerced to at least
 * 1 so a misconfigured `SERVICE_MAX_HISTORY` (0 or negative) can never drop the user's own turn or crash.
 */
class ChatSessions(maxHistory: Int) {

    private val maxHistory = maxHistory.coerceAtLeast(1)
    private val sessions = ConcurrentHashMap<String, MutableList<Message>>()

    /** A fresh, unused session id. */
    fun newId(): String = UUID.randomUUID().toString()

    /**
     * Appends [message] to [sessionId]'s history (creating it if new), trims to the last [maxHistory]
     * messages, and returns an immutable snapshot of the resulting history to send to the model.
     */
    fun appendAndSnapshot(sessionId: String, message: Message): List<Message> {
        val history = sessions.computeIfAbsent(sessionId) { mutableListOf() }
        synchronized(history) {
            history.add(message)
            trim(history)
            return history.toList()
        }
    }

    /** Appends without returning a snapshot — for the assistant turn, whose snapshot is unused. */
    fun append(sessionId: String, message: Message) {
        appendAndSnapshot(sessionId, message)
    }

    /** Read-only snapshot of [sessionId]'s history (empty if unknown) — for tests/inspection. */
    fun snapshot(sessionId: String): List<Message> {
        val history = sessions[sessionId] ?: return emptyList()
        synchronized(history) { return history.toList() }
    }

    private fun trim(history: MutableList<Message>) {
        if (history.size > maxHistory) history.subList(0, history.size - maxHistory).clear()
    }
}
