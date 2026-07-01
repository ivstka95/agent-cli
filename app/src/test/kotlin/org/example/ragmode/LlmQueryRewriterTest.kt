package org.example.ragmode

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LlmQueryRewriterTest {

    @Test
    fun `rewrites the query via the LLM and passes it the raw question`() = runBlocking {
        val llm = RecordingLlmClient(reply = "JsonVectorIndex JSON serialization on disk persistence")

        val rewritten = LlmQueryRewriter(llm).transform("how are indexes stored?")

        assertEquals("JsonVectorIndex JSON serialization on disk persistence", rewritten)
        assertEquals(LlmQueryRewriter.REWRITE_SYSTEM, llm.systemPrompt)
        assertEquals("how are indexes stored?", llm.messages.single().content) // raw question, unmodified
    }

    @Test
    fun `trims surrounding whitespace from the rewrite`() = runBlocking {
        val rewritten = LlmQueryRewriter(RecordingLlmClient(reply = "  expanded query  \n")).transform("q")
        assertEquals("expanded query", rewritten)
    }

    @Test
    fun `falls back to the original query when the rewrite is blank`() = runBlocking {
        val rewritten = LlmQueryRewriter(RecordingLlmClient(reply = "   ")).transform("original question")
        assertEquals("original question", rewritten)
    }

    @Test
    fun `falls back to the original query when the LLM call fails`() = runBlocking {
        val rewritten = LlmQueryRewriter(RecordingLlmClient(fail = true)).transform("original question")
        assertEquals("original question", rewritten)
    }
}
