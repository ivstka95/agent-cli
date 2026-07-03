package org.example.agent

import kotlinx.coroutines.runBlocking
import org.example.memory.MemoryStore
import org.example.rag.index.SearchResult
import org.example.rag.model.Chunk
import org.example.rag.model.ChunkMetadata
import org.example.rag.retrieve.RagRetriever
import org.example.rag.retrieve.RetrievalResult
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records the assembled system prompt and returns a canned reply — no network. */
private class RecordingGenerator : ResponseGenerator {
    var receivedSystemPrompt: String? = null

    override suspend fun generate(systemPrompt: String, messages: List<Message>, currentTask: String?): GeneratedResponse {
        receivedSystemPrompt = systemPrompt
        return GeneratedResponse("ok", taskUpdate = null, inputTokens = 1, outputTokens = 1)
    }
}

/** Canned-hits retriever that records how it was invoked. [fail] makes retrieve() throw. */
private class FakeRagRetriever(
    private val hits: List<SearchResult> = emptyList(),
    private val fail: Boolean = false,
) : RagRetriever {
    var calls = 0
    var lastTopK = -1
    var lastQuestion: String? = null

    override suspend fun retrieve(question: String, topK: Int): RetrievalResult {
        calls++
        lastTopK = topK
        lastQuestion = question
        if (fail) throw IllegalStateException("ollama down")
        return RetrievalResult(hits, retrievedCount = hits.size)
    }
}

class AgentRagTest {

    private val root: File = createTempDirectory("agent-rag").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun hit(file: String, section: String?, text: String): SearchResult =
        SearchResult(
            Chunk(
                text = text,
                metadata = ChunkMetadata(
                    source = "app/src/$file", file = file, section = section,
                    chunkId = "app/src/$file#structural#0", strategy = "structural", ordinal = 0,
                ),
            ),
            0.9f,
        )

    private fun memoryWithTask(goal: String): MemoryStore {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        memory.working.overwriteActive("# Task: demo\nstage: planning\nstage_complete: false\n\n## Goal\n$goal\n")
        return memory
    }

    @Test
    fun `retrieved context and task memory both appear in the system prompt`() = runBlocking {
        val memory = memoryWithTask("GOAL_MARKER")
        val gen = RecordingGenerator()
        val retriever = FakeRagRetriever(listOf(hit("Agent.kt", "buildSystemPrompt", "assembles the system prompt")))
        val agent = Agent(gen, memory, ragRetriever = retriever, ragSearchK = 20)

        agent.run("How is the prompt built?", history = emptyList(), useRag = true)

        val prompt = gen.receivedSystemPrompt!!
        assertTrue(prompt.contains("GOAL_MARKER"), "task-memory goal missing from prompt")
        assertTrue(prompt.contains("# Retrieved context (RAG)"), "RAG section header missing")
        assertTrue(prompt.contains("[Source: Agent.kt, section: buildSystemPrompt]"), "retrieved context missing")
        assertTrue(prompt.contains("assembles the system prompt"), "chunk text missing")
        // The grounding / anti-hallucination instruction accompanies the retrieved context.
        assertTrue(prompt.contains("Ground your answer"), "grounding instruction missing")
        assertTrue(prompt.contains("do not actually answer the question"), "anti-hallucination instruction missing")
    }

    @Test
    fun `sources from the retrieved hits are attached to the response deterministically`() = runBlocking {
        val memory = memoryWithTask("g")
        val retriever = FakeRagRetriever(
            listOf(
                hit("Agent.kt", "buildSystemPrompt", "a"),
                hit("Agent.kt", "buildSystemPrompt", "b"), // duplicate file+section collapses
                hit("Repl.kt", null, "c"),
            ),
        )
        val agent = Agent(RecordingGenerator(), memory, ragRetriever = retriever, ragSearchK = 20)

        val response = agent.run("q", history = emptyList(), useRag = true)

        assertEquals(listOf("Agent.kt:buildSystemPrompt", "Repl.kt:—"), response.sources)
    }

    @Test
    fun `the retriever is called once per turn with the configured searchK and the user question`() = runBlocking {
        val memory = memoryWithTask("g")
        val retriever = FakeRagRetriever(listOf(hit("Agent.kt", "run", "x")))
        val agent = Agent(RecordingGenerator(), memory, ragRetriever = retriever, ragSearchK = 20)

        agent.run("first question", history = emptyList(), useRag = true)
        agent.run("second question", history = emptyList(), useRag = true)

        assertEquals(2, retriever.calls) // exactly once per turn
        assertEquals(20, retriever.lastTopK) // searchK == ragSearchK
        assertEquals("second question", retriever.lastQuestion)
    }

    @Test
    fun `useRag off skips retrieval - no rag layer and no sources`() = runBlocking {
        val memory = memoryWithTask("g")
        val gen = RecordingGenerator()
        val retriever = FakeRagRetriever(listOf(hit("Agent.kt", "run", "x")))
        val agent = Agent(gen, memory, ragRetriever = retriever, ragSearchK = 20)

        val response = agent.run("q", history = emptyList(), useRag = false)

        assertEquals(0, retriever.calls)
        assertFalse(gen.receivedSystemPrompt!!.contains("# Retrieved context (RAG)"))
        assertTrue(response.sources.isEmpty())
    }

    @Test
    fun `a retrieval failure degrades gracefully to a plain reply`() = runBlocking {
        val memory = memoryWithTask("g")
        val gen = RecordingGenerator()
        val agent = Agent(gen, memory, ragRetriever = FakeRagRetriever(fail = true), ragSearchK = 20)

        val response = agent.run("q", history = emptyList(), useRag = true) // must not throw

        assertEquals("ok", response.assistantText)
        assertFalse(gen.receivedSystemPrompt!!.contains("# Retrieved context (RAG)"), "no RAG layer on failure")
        assertTrue(response.sources.isEmpty())
    }

    @Test
    fun `retrieved context is injected even with no active task`() = runBlocking {
        val memory = MemoryStore(root) // no task created
        val gen = RecordingGenerator()
        val retriever = FakeRagRetriever(listOf(hit("Repl.kt", "submit", "routes input")))
        val agent = Agent(gen, memory, ragRetriever = retriever, ragSearchK = 20)

        val response = agent.run("q", history = emptyList(), useRag = true)

        assertTrue(gen.receivedSystemPrompt!!.contains("[Source: Repl.kt, section: submit]"))
        assertEquals(listOf("Repl.kt:submit"), response.sources)
    }
}
