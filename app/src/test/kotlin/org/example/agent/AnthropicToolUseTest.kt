package org.example.agent

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.llm.AnthropicClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Verifies the Anthropic native tool-use wiring in [AnthropicClient.runToolTurn] via a Ktor
 * MockEngine: the outgoing request carries `tools` and uses auto tool_choice (no forced
 * `tool_choice`), and the response is mapped to the right [LlmTurn].
 */
class AnthropicToolUseTest {

    private val tools = listOf(
        ToolSpec(
            name = "get_recent_commits",
            description = "fetch commits",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("owner") { put("type", "string") } }
            },
        ),
    )

    @Test
    fun `request carries tools and auto tool_choice, tool_use response maps to ToolRequests`() = runBlocking {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = String(request.body.toByteArray())
            respond(
                content = """
                    {"stop_reason":"tool_use","content":[
                      {"type":"text","text":"Let me check."},
                      {"type":"tool_use","id":"tu_1","name":"get_recent_commits","input":{"owner":"JetBrains","repo":"kotlin"}}
                    ],"usage":{"input_tokens":11,"output_tokens":4}}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = AnthropicClient(apiKey = "test-key", engine = engine)

        val turn = client.runToolTurn(
            systemPrompt = "sys",
            messages = listOf(Message(Role.USER, "latest commits?")),
            exchanges = emptyList(),
            tools = tools,
        )

        // Request shape: tools present, named tool, and NO forced tool_choice (auto).
        assertTrue(capturedBody.contains("\"tools\""), capturedBody)
        assertTrue(capturedBody.contains("get_recent_commits"), capturedBody)
        assertTrue(capturedBody.contains("input_schema"), capturedBody)
        assertTrue(!capturedBody.contains("tool_choice"), "auto tool-use must not send tool_choice: $capturedBody")

        // Response mapping: a tool_use stop maps to ToolRequests with parsed id/name/args.
        assertIs<LlmTurn.ToolRequests>(turn)
        val use = turn.toolUses.single()
        assertEquals("tu_1", use.id)
        assertEquals("get_recent_commits", use.name)
        assertTrue(use.argsJson.contains("JetBrains"), use.argsJson)
        assertEquals(11, turn.inputTokens)
        assertEquals(4, turn.outputTokens)
    }

    @Test
    fun `a text response (end_turn) maps to Answer`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"stop_reason":"end_turn","content":[{"type":"text","text":"No tools needed."}],"usage":{"input_tokens":2,"output_tokens":3}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = AnthropicClient(apiKey = "test-key", engine = engine)

        val turn = client.runToolTurn("sys", listOf(Message(Role.USER, "hi")), emptyList(), tools)

        assertIs<LlmTurn.Answer>(turn)
        assertEquals("No tools needed.", turn.text)
        assertEquals(2, turn.inputTokens)
        assertEquals(3, turn.outputTokens)
    }

    @Test
    fun `prior exchanges are serialized as tool_use and tool_result content blocks`() = runBlocking {
        var capturedBody = ""
        val engine = MockEngine { request ->
            capturedBody = String(request.body.toByteArray())
            respond(
                content = """{"stop_reason":"end_turn","content":[{"type":"text","text":"done"}],"usage":{"input_tokens":1,"output_tokens":1}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = AnthropicClient(apiKey = "test-key", engine = engine)

        val exchanges = listOf(
            ToolExchange(
                uses = listOf(ToolUseRequest("tu_1", "get_recent_commits", """{"owner":"JetBrains"}""")),
                results = listOf(ToolResult("tu_1", "Recent commits: ...", isError = false)),
            ),
        )

        client.runToolTurn("sys", listOf(Message(Role.USER, "go")), exchanges, tools)

        assertTrue(capturedBody.contains("\"type\":\"tool_use\""), capturedBody)
        assertTrue(capturedBody.contains("\"type\":\"tool_result\""), capturedBody)
        assertTrue(capturedBody.contains("\"tool_use_id\":\"tu_1\""), capturedBody)
    }
}
