package org.example.service

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * [Day 30] Drives the real routing layer offline via Ktor `testApplication` (no socket, no live Ollama):
 * confirms `GET /` serves the page, `POST /chat` returns the DTOs and continues a session on echo, and
 * the two failure modes map to the right status codes (429 rate-limited, 503 Ollama-down) without the
 * server crashing.
 */
class ChatServerRouteTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildService(
        llm: FakeLlmClient = FakeLlmClient(),
        rateLimit: Int = 100,
    ) = ChatService(
        llm = llm,
        sessions = ChatSessions(maxHistory = 20),
        rateLimiter = RateLimiter(rateLimit),
        maxInputChars = 4000,
    )

    private suspend fun ApplicationTestBuilder.postChat(message: String, sessionId: String? = null): HttpResponse =
        client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ChatRequest(sessionId, message)))
        }

    @Test
    fun `GET root serves the chat page`() {
        testApplication {
            application { chatModule(buildService(), "<html>CHAT</html>") }
            val res = client.get("/")
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.bodyAsText().contains("CHAT"))
        }
    }

    @Test
    fun `POST chat returns a reply and continues the session on echo`() {
        testApplication {
            val llm = FakeLlmClient(reply = "hello there")
            application { chatModule(buildService(llm), "<html></html>") }

            val first = postChat("hi")
            assertEquals(HttpStatusCode.OK, first.status)
            val body = json.decodeFromString<ChatResponse>(first.bodyAsText())
            assertEquals("hello there", body.reply)
            assertTrue(body.sessionId.isNotBlank())

            // Echo the id → the same session continues (the model sees the prior turn + the new one).
            val second = postChat("more", sessionId = body.sessionId)
            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals(body.sessionId, json.decodeFromString<ChatResponse>(second.bodyAsText()).sessionId)
            assertEquals(3, llm.lastMessages.size)
        }
    }

    @Test
    fun `over the rate limit returns 429`() {
        testApplication {
            application { chatModule(buildService(rateLimit = 1), "<html></html>") }

            assertEquals(HttpStatusCode.OK, postChat("first").status)

            val second = postChat("second")
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
            assertTrue(second.bodyAsText().contains("error"))
        }
    }

    @Test
    fun `a malformed body returns 400, not 500`() {
        testApplication {
            application { chatModule(buildService(), "<html></html>") }

            val res = client.post("/chat") {
                contentType(ContentType.Application.Json)
                setBody("{ not valid json") // unparseable → caller's fault
            }
            assertEquals(HttpStatusCode.BadRequest, res.status)
            assertTrue(res.bodyAsText().contains("error"))
        }
    }

    @Test
    fun `Ollama down returns 503 and the server stays up`() {
        testApplication {
            application { chatModule(buildService(FakeLlmClient(fail = true)), "<html></html>") }

            val res = postChat("hi")
            assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
            assertTrue(res.bodyAsText().contains("Ollama"))

            // The server is still serving after the error.
            assertEquals(HttpStatusCode.OK, client.get("/").status)
        }
    }
}
