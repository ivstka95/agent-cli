package org.example.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.agent.Message
import org.example.agent.Role

/**
 * [Day 30] Specs for the session store's trimming/robustness — the concurrency is covered indirectly by
 * the service tests; here we pin the [maxHistory] cap and its guard against a misconfigured value.
 */
class ChatSessionsTest {

    @Test
    fun `keeps only the last maxHistory messages`() {
        val sessions = ChatSessions(maxHistory = 2)
        val id = sessions.newId()
        repeat(5) { sessions.append(id, Message(Role.USER, "m$it")) }

        assertEquals(listOf("m3", "m4"), sessions.snapshot(id).map { it.content })
    }

    @Test
    fun `a zero maxHistory is clamped so the message is still retained`() {
        val sessions = ChatSessions(maxHistory = 0)
        val id = sessions.newId()
        val snapshot = sessions.appendAndSnapshot(id, Message(Role.USER, "hi"))

        assertEquals(listOf("hi"), snapshot.map { it.content })
    }

    @Test
    fun `a negative maxHistory does not crash and keeps at least one message`() {
        val sessions = ChatSessions(maxHistory = -1)
        val id = sessions.newId()
        sessions.append(id, Message(Role.USER, "one"))
        sessions.append(id, Message(Role.ASSISTANT, "two"))

        assertTrue(sessions.snapshot(id).isNotEmpty())
    }
}
