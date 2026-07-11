package org.example.day25

import kotlinx.coroutines.runBlocking
import org.example.agent.Agent
import org.example.agent.CombinedResponseGenerator
import org.example.agent.Message
import org.example.agent.Role
import org.example.llm.LlmClientFactory
import org.example.memory.MemoryStore
import org.example.rag.config.RagConfig
import org.example.ragmode.agentRagRetriever
import kotlin.io.path.createTempDirectory
import kotlin.system.exitProcess

/**
 * [Day 25] Verification runner — a SEPARATE run mode from the interactive REPL (`org.example.MainKt`),
 * mirroring `runRagEval`. It drives the AGENT (task memory + RAG + history) through the two long
 * scenarios over THIS codebase, one scripted question at a time, accumulating conversation history.
 *
 * Per scenario it seeds the dialogue Goal (task memory), then for each of 10–15 turns prints the
 * grounded answer and its `sources`, and re-reads the Goal from the task file to prove it never drifts.
 * A per-scenario summary reports whether the goal was retained on every turn and every answer was
 * sourced — the two Day-25 acceptance criteria (the assistant doesn't lose the goal and stays sourced).
 *
 * Requires `ANTHROPIC_API_KEY`, a running Ollama, and the index built (`./gradlew :rag:runIndexer`).
 * Runs against an ISOLATED temp memory dir so it never touches the real `memory/`. Config via
 * [RagConfig.fromEnv].
 */
fun main() = runBlocking {
    // [Day 27] LLM_PROVIDER (anthropic|ollama, default anthropic) picks the backend for the whole run.
    val llmClient = try {
        LlmClientFactory.fromEnv()
    } catch (e: IllegalStateException) {
        System.err.println(e.message)
        exitProcess(1)
    }

    val config = RagConfig.fromEnv()
    val generator = CombinedResponseGenerator(llmClient)
    val (retriever, retrieverCloser) = agentRagRetriever(llmClient, config)

    // Isolated memory so the eval never clobbers the user's real working tasks / profile.
    val memoryRoot = createTempDirectory("day25-eval").toFile()
    val memory = MemoryStore(memoryRoot)
    val agent = Agent(generator, memory, ragRetriever = retriever, ragSearchK = config.retrieveK)

    val scenarios = Day25Scenario.load()
    println(
        "Day-25 verification — ${scenarios.size} scenarios · index: ${config.indexStrategy.fileName} · " +
            "improved retrieval: rewrite + retrieveK=${config.retrieveK} → threshold ${config.scoreThreshold} " +
            "→ afterK=${config.afterK}",
    )
    println("=".repeat(100))

    try {
        scenarios.forEach { runScenario(agent, memory, it) }
    } finally {
        retrieverCloser.close()
        memoryRoot.deleteRecursively()
    }
}

private suspend fun runScenario(agent: Agent, memory: MemoryStore, scenario: Day25Scenario) {
    // Fresh task + history per scenario so the two dialogues don't bleed into each other. Set the
    // dialogue goal (task memory) up front; from here it is code-owned and can't drift.
    memory.shortTerm.clear()
    memory.working.createTask(scenario.name)
    memory.working.setActiveGoal(scenario.goal)

    println("\nSCENARIO: ${scenario.name}")
    println("GOAL: ${scenario.goal}")
    println("-".repeat(100))

    var sourcedTurns = 0
    var goalRetainedTurns = 0
    val goal = scenario.goal.trim()

    scenario.questions.forEachIndexed { i, question ->
        val response = agent.run(question, memory.shortTerm.history(), useRag = true)
        memory.shortTerm.add(Message(Role.USER, question))
        memory.shortTerm.add(Message(Role.ASSISTANT, response.assistantText))

        val goalStillThere = memory.working.activeGoal() == goal
        if (response.sources.isNotEmpty()) sourcedTurns++
        if (goalStillThere) goalRetainedTurns++

        println("\nQ${i + 1}. $question")
        println(indent(response.assistantText))
        println("  sources: ${if (response.sources.isEmpty()) "(none)" else response.sources.joinToString(", ")}")
        println("  [goal retained: ${if (goalStillThere) "✓" else "✗ DRIFTED"}]")
    }

    val n = scenario.questions.size
    println("\n[${scenario.name}] SUMMARY — sourced turns: $sourcedTurns/$n · goal retained: $goalRetainedTurns/$n")
    println("GOAL (final, from the task file): ${memory.working.activeGoal()}")
    val pass = sourcedTurns == n && goalRetainedTurns == n
    println("RESULT: ${if (pass) "PASS ✓ (every turn sourced, goal never lost)" else "CHECK ✗ (see turns above)"}")
    println("=".repeat(100))
}

private fun indent(text: String): String = text.lineSequence().joinToString("\n") { "  $it" }
