package org.example.llm

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.agent.Message
import org.example.agent.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies [OllamaLlmClient]'s `/api/chat` wiring via a Ktor MockEngine — no live Ollama. Covers the
 * request shape (endpoint, `stream:false`, a leading `system` message), token mapping, structured output
 * (`format` + verbatim content), token defaults, and readable errors.
 */
class OllamaLlmClientTest {

    private fun MockRequestHandleScope.jsonOk(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    @Test
    fun `complete maps content and tokens and sends a system + user chat request`() = runBlocking {
        var url = ""
        var body = ""
        val engine = MockEngine { request ->
            url = request.url.toString()
            body = String(request.body.toByteArray())
            jsonOk("""{"message":{"role":"assistant","content":"hi there"},"prompt_eval_count":34,"eval_count":12,"done":true}""")
        }
        val client = OllamaLlmClient(LlmConfig(), engine = engine)

        val result = client.complete("you are helpful", listOf(Message(Role.USER, "hello")))

        assertEquals("hi there", result.replyText)
        assertEquals(34, result.inputTokens)
        assertEquals(12, result.outputTokens)
        assertTrue(url.endsWith("/api/chat"), url)
        assertTrue(body.contains("\"stream\":false"), body)
        assertTrue(body.contains("\"role\":\"system\""), body)
        assertTrue(body.contains("you are helpful"), body)
        assertTrue(body.contains("hello"), body)
    }

    @Test
    fun `completeStructured sends the schema as format, folds the tool description, and returns content verbatim`() = runBlocking {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("answer") { put("type", "string") } }
        }
        var body = ""
        // message.content is the JSON string produced under `format` (note the escaped inner quotes).
        val engine = MockEngine { request ->
            body = String(request.body.toByteArray())
            jsonOk("""{"message":{"content":"{\"answer\":\"grounded\"}"},"prompt_eval_count":5,"eval_count":7}""")
        }
        val client = OllamaLlmClient(LlmConfig(), engine = engine)

        val result = client.completeStructured(
            systemPrompt = "answer from context",
            messages = listOf(Message(Role.USER, "q")),
            toolName = "answer_tool",
            toolDescription = "Quote verbatim from context.",
            inputSchema = schema,
        )

        assertEquals("""{"answer":"grounded"}""", result.toolInputJson)
        assertEquals(5, result.inputTokens)
        assertEquals(7, result.outputTokens)
        assertTrue(body.contains("\"format\""), body)
        assertTrue(body.contains("answer"), body) // the schema property name is present
        assertTrue(body.contains("answer_tool"), body) // tool name folded into the system message
        assertTrue(body.contains("Quote verbatim from context."), body) // tool description folded in
        assertTrue(body.contains("\"stream\":false"), body)
    }

    @Test
    fun `absent token counts default to zero`() = runBlocking {
        val engine = MockEngine { jsonOk("""{"message":{"content":"ok"}}""") }
        val client = OllamaLlmClient(LlmConfig(), engine = engine)

        val result = client.complete("s", listOf(Message(Role.USER, "q")))

        assertEquals("ok", result.replyText)
        assertEquals(0, result.inputTokens)
        assertEquals(0, result.outputTokens)
    }

    @Test
    fun `a non-2xx response surfaces a readable Ollama error`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = "model 'llama3.2' not found",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "text/plain"),
            )
        }
        val client = OllamaLlmClient(LlmConfig(), engine = engine)

        val ex = assertFailsWith<RuntimeException> {
            client.complete("s", listOf(Message(Role.USER, "q")))
        }
        assertTrue(ex.message!!.contains("Ollama chat error 500"), ex.message)
    }
}
