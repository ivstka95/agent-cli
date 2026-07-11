package org.example.agent

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.example.mcp.McpClient
import org.example.mcp.ServerInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hand-written fakes (no mocking library). The LLM is scripted to a sequence of [LlmTurn]s and
 * records the [ToolExchange]s it was handed; the MCP client records calls and returns canned text.
 */
private class ScriptedToolLlm(private val turns: List<LlmTurn>) : LlmClient {
    var turnCalls = 0
        private set
    val seenExchanges = mutableListOf<List<ToolExchange>>()

    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult =
        error("not used")

    override suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult = error("not used")

    override suspend fun runToolTurn(
        systemPrompt: String,
        messages: List<Message>,
        exchanges: List<ToolExchange>,
        tools: List<ToolSpec>,
    ): LlmTurn {
        seenExchanges += exchanges.toList()
        return turns[minOf(turnCalls, turns.lastIndex)].also { turnCalls++ }
    }
}

/** A tool-less provider (local Ollama): runToolTurn is left as the interface default (throws). */
private class ToollessLlm(private val reply: String) : LlmClient {
    override suspend fun complete(systemPrompt: String, messages: List<Message>): LlmResult =
        LlmResult(reply, inputTokens = 2, outputTokens = 3)

    override suspend fun completeStructured(
        systemPrompt: String,
        messages: List<Message>,
        toolName: String,
        toolDescription: String,
        inputSchema: JsonObject,
    ): StructuredResult = error("not used")
}

private class FakeMcpClient(private val cannedText: String) : McpClient {
    val calls = mutableListOf<Pair<String, Map<String, Any?>>>()

    override suspend fun connect() {}
    override val serverInfo: ServerInfo? = null
    override suspend fun listTools(): List<Tool> = emptyList()
    override suspend fun callTool(name: String, arguments: Map<String, Any?>): CallToolResult {
        calls += name to arguments
        return CallToolResult(content = listOf(TextContent(cannedText)), isError = false)
    }
    override suspend fun close() {}
}

class AgenticLoopTest {

    private val tools = listOf(ToolSpec("get_recent_commits", "desc", emptySchema()))

    @Test
    fun `executes a tool call, feeds the result back, and returns the final answer`() = runBlocking {
        val llm = ScriptedToolLlm(
            listOf(
                LlmTurn.ToolRequests(
                    listOf(ToolUseRequest(id = "tu_1", name = "get_recent_commits", argsJson = """{"owner":"JetBrains","repo":"kotlin","limit":2}""")),
                    inputTokens = 5,
                    outputTokens = 7,
                ),
                LlmTurn.Answer("Here are the latest commits: ...", inputTokens = 3, outputTokens = 9),
            ),
        )
        val mcp = FakeMcpClient(cannedText = "Recent commits for JetBrains/kotlin (2): ...")
        val loop = AgenticLoop(llm, mcp, tools)

        val result = loop.run("system", listOf(Message(Role.USER, "latest commits on JetBrains/kotlin?")))

        // The tool was called with the model's parsed arguments.
        assertEquals(1, mcp.calls.size)
        val (name, args) = mcp.calls.single()
        assertEquals("get_recent_commits", name)
        assertEquals("JetBrains", args["owner"])
        assertEquals("kotlin", args["repo"])
        assertEquals(2L, args["limit"]) // JSON integers map to Long

        // The second LLM turn received the tool_result fed back.
        assertEquals(2, llm.turnCalls)
        val secondTurnExchanges = llm.seenExchanges[1]
        assertEquals(1, secondTurnExchanges.size)
        val fedResult = secondTurnExchanges.single().results.single()
        assertEquals("tu_1", fedResult.toolUseId)
        assertTrue(fedResult.content.contains("Recent commits"))
        assertEquals(false, fedResult.isError)

        // Final answer + summed token usage.
        assertEquals("Here are the latest commits: ...", result.reply)
        assertEquals(8, result.inputTokens)
        assertEquals(16, result.outputTokens)
    }

    @Test
    fun `the max-iteration guard halts a runaway tool loop`() = runBlocking {
        // The model always asks for a tool and never answers.
        val alwaysTool = ScriptedToolLlm(
            listOf(
                LlmTurn.ToolRequests(
                    listOf(ToolUseRequest("tu", "get_recent_commits", """{"owner":"a","repo":"b"}""")),
                    inputTokens = 1,
                    outputTokens = 1,
                ),
            ),
        )
        val mcp = FakeMcpClient(cannedText = "ok")
        val loop = AgenticLoop(alwaysTool, mcp, tools, maxIterations = 3)

        val result = loop.run("system", listOf(Message(Role.USER, "go")))

        // Exactly maxIterations LLM turns and tool calls, then the guard message.
        assertEquals(3, alwaysTool.turnCalls)
        assertEquals(3, mcp.calls.size)
        assertTrue(result.reply.contains("within 3 steps"), result.reply)
    }

    @Test
    fun `a tool failure becomes an error result fed back, not a thrown exception`() = runBlocking {
        val llm = ScriptedToolLlm(
            listOf(
                LlmTurn.ToolRequests(listOf(ToolUseRequest("tu", "get_recent_commits", "{}")), 1, 1),
                LlmTurn.Answer("done", 1, 1),
            ),
        )
        val mcp = object : McpClient {
            var calls = 0
            override suspend fun connect() {}
            override val serverInfo: ServerInfo? = null
            override suspend fun listTools(): List<Tool> = emptyList()
            override suspend fun callTool(name: String, arguments: Map<String, Any?>): CallToolResult {
                calls++
                throw RuntimeException("boom")
            }
            override suspend fun close() {}
        }
        val loop = AgenticLoop(llm, mcp, tools)

        val result = loop.run("system", listOf(Message(Role.USER, "go")))

        assertEquals(1, mcp.calls)
        // The failure was fed back as an error tool_result; the loop continued to a final answer.
        val fed = llm.seenExchanges[1].single().results.single()
        assertEquals(true, fed.isError)
        assertTrue(fed.content.contains("boom"), fed.content)
        assertEquals("done", result.reply)
    }

    @Test
    fun `a tool-less provider degrades to a plain reply instead of crashing`() = runBlocking {
        // [Day 27] Switching to a local model (no runToolTurn) must not blow up the agentic path.
        val llm = ToollessLlm("plain fallback")
        val mcp = FakeMcpClient(cannedText = "unused")
        val loop = AgenticLoop(llm, mcp, tools)

        val result = loop.run("system", listOf(Message(Role.USER, "hi")))

        assertEquals("plain fallback", result.reply)
        assertEquals(2, result.inputTokens)
        assertEquals(3, result.outputTokens)
        assertEquals(0, mcp.calls.size) // no tool ever executed
    }

    private fun emptySchema(): JsonObject = JsonObject(emptyMap())
}
