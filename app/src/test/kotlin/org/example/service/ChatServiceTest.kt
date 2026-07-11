package org.example.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.example.agent.Role

/**
 * [Day 30] Specs for the chat service core — all offline with a [FakeLlmClient], no Ktor, no live Ollama.
 * Covers session accrual + isolation, new-session ids, the max-context limits (input truncation + history
 * trim), and graceful Ollama-down handling.
 */
class ChatServiceTest {

    private fun service(
        llm: FakeLlmClient = FakeLlmClient(),
        maxHistory: Int = 20,
        maxInputChars: Int = 4000,
        rateLimit: Int = 100,
    ): Pair<ChatService, ChatSessions> {
        val sessions = ChatSessions(maxHistory)
        val svc = ChatService(
            llm = llm,
            sessions = sessions,
            rateLimiter = RateLimiter(rateLimit),
            maxInputChars = maxInputChars,
        )
        return svc to sessions
    }

    @Test
    fun `history accrues across turns within one session`() {
        runBlocking {
            val llm = FakeLlmClient(reply = "R")
            val (svc, _) = service(llm)

            val first = svc.handle(sessionId = null, clientKey = "ip1", message = "hello")
            val id = (first as ChatOutcome.Reply).sessionId

            svc.handle(sessionId = id, clientKey = "ip1", message = "again")

            // The second call sends the full prior turn + the new user message.
            assertEquals(3, llm.lastMessages.size)
            assertEquals(Role.USER, llm.lastMessages[0].role)
            assertEquals("hello", llm.lastMessages[0].content)
            assertEquals(Role.ASSISTANT, llm.lastMessages[1].role)
            assertEquals("R", llm.lastMessages[1].content)
            assertEquals(Role.USER, llm.lastMessages[2].role)
            assertEquals("again", llm.lastMessages[2].content)
        }
    }

    @Test
    fun `two sessions stay isolated`() {
        runBlocking {
            val llm = FakeLlmClient()
            val (svc, sessions) = service(llm)

            val a = (svc.handle(null, "ipA", "from A") as ChatOutcome.Reply).sessionId
            val b = (svc.handle(null, "ipB", "from B") as ChatOutcome.Reply).sessionId

            assertNotEquals(a, b)
            assertEquals(listOf("from A", "ok"), sessions.snapshot(a).map { it.content })
            assertEquals(listOf("from B", "ok"), sessions.snapshot(b).map { it.content })
        }
    }

    @Test
    fun `a fresh session gets a new id, distinct per caller`() {
        runBlocking {
            val (svc, _) = service()
            val first = svc.handle(null, "ip1", "hi") as ChatOutcome.Reply
            val second = svc.handle(null, "ip1", "hi") as ChatOutcome.Reply

            assertTrue(first.sessionId.isNotBlank())
            assertNotEquals(first.sessionId, second.sessionId)
        }
    }

    @Test
    fun `an over-long input is truncated to maxInputChars`() {
        runBlocking {
            val llm = FakeLlmClient()
            val (svc, _) = service(llm, maxInputChars = 10)

            svc.handle(null, "ip1", message = "x".repeat(50))

            assertEquals(10, llm.lastMessages.last().content.length)
        }
    }

    @Test
    fun `history is trimmed to the last maxHistory messages`() {
        runBlocking {
            val llm = FakeLlmClient(reply = "r")
            val (svc, sessions) = service(llm, maxHistory = 2)

            val id = (svc.handle(null, "ip1", "one") as ChatOutcome.Reply).sessionId
            svc.handle(id, "ip1", "two")
            svc.handle(id, "ip1", "three")

            // Each turn adds a user + assistant message; maxHistory=2 keeps only the last two.
            assertEquals(2, sessions.snapshot(id).size)
            assertEquals(listOf("three", "r"), sessions.snapshot(id).map { it.content })
        }
    }

    @Test
    fun `Ollama down yields an LlmError, not a thrown exception`() {
        runBlocking {
            val llm = FakeLlmClient(fail = true)
            val (svc, _) = service(llm)

            val outcome = svc.handle(null, "ip1", "hi")

            assertTrue(outcome is ChatOutcome.LlmError)
            assertTrue(outcome.error.contains("Ollama"))
        }
    }

    @Test
    fun `over the rate limit yields RateLimited`() {
        runBlocking {
            val (svc, _) = service(rateLimit = 1)
            svc.handle(null, "ip1", "first")
            val second = svc.handle(null, "ip1", "second")
            assertTrue(second is ChatOutcome.RateLimited)
        }
    }
}
