package org.example.agent

import kotlinx.coroutines.runBlocking
import org.example.memory.MemoryStore
import org.example.task.TaskHeader
import org.example.task.TaskState
import org.example.task.TransitionMode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scripted [ResponseGenerator]: returns a fixed queue of responses (clamping to
 * the last once exhausted), counts calls, and records the system prompts it saw.
 * Drives the Agent's autonomous chain without a network and lets us assert exactly
 * how many model calls happened.
 */
private class ScriptedResponseGenerator(
    private val responses: List<GeneratedResponse>,
) : ResponseGenerator {
    var calls = 0
        private set
    val systemPrompts = mutableListOf<String>()

    override suspend fun generate(
        systemPrompt: String,
        messages: List<Message>,
        currentTask: String?,
    ): GeneratedResponse {
        systemPrompts += systemPrompt
        val response = responses[minOf(calls, responses.lastIndex)]
        calls++
        return response
    }
}

class AgentTransitionTest {

    private val root: File = createTempDirectory("agent-transition").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    /** A full task document with the given stage and section bodies (default = empty placeholder). */
    private fun task(
        stage: String,
        complete: String = "false",
        req: String = "-",
        dec: String = "-",
        impl: String = "-",
        valid: String = "-",
    ) = """
        # Task: demo
        stage: $stage
        stage_complete: $complete
        step:
        expected_action:

        ## Goal
        Encrypt tokens

        ## Requirements
        $req

        ## Decisions
        $dec

        ## Implementation
        $impl

        ## Validation
        $valid

        ## Done
        -

        ## TODO
        -
    """.trimIndent()

    private fun complete(reply: String, taskUpdate: String) =
        GeneratedResponse(reply, taskUpdate = taskUpdate, inputTokens = 1, outputTokens = 1, stageComplete = true)

    /** [Day 15] A complete response that also PROPOSES a direction (forward or backward). */
    private fun proposing(reply: String, taskUpdate: String, target: TaskState) =
        GeneratedResponse(
            reply,
            taskUpdate = taskUpdate,
            inputTokens = 1,
            outputTokens = 1,
            stageComplete = true,
            proposedTransition = target,
        )

    private fun activeStage(memory: MemoryStore): TaskState =
        TaskHeader.parse(memory.working.activeTaskContent()!!).stage

    private fun activeStageComplete(memory: MemoryStore): Boolean =
        TaskHeader.parse(memory.working.activeTaskContent()!!).stageComplete

    private fun activeProposed(memory: MemoryStore): TaskState? =
        TaskHeader.parse(memory.working.activeTaskContent()!!).proposedTransition

    @Test
    fun `chain advances through every stage to DONE in a single turn`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        val gen = ScriptedResponseGenerator(
            listOf(
                complete("planned", task("planning", req = "- R", dec = "- D")),
                complete("executed", task("execution", req = "- R", dec = "- D", impl = "- I")),
                complete("validated", task("validation", req = "- R", dec = "- D", impl = "- I", valid = "- V")),
            ),
        )
        val agent = Agent(gen, memory)

        val response = agent.run("drive it", history = emptyList(), mode = TransitionMode.AUTO)

        // One call per stage, no extra calls (each artifact was ready → no self-correction).
        assertEquals(3, gen.calls)
        assertEquals(listOf(TaskState.PLANNING, TaskState.EXECUTION, TaskState.VALIDATION), response.steps.map { it.stage })
        assertEquals(TaskState.EXECUTION, response.steps[0].transition?.to)
        assertEquals(TaskState.VALIDATION, response.steps[1].transition?.to)
        assertEquals(TaskState.DONE, response.steps[2].transition?.to)
        assertEquals(TaskState.DONE, activeStage(memory))
        assertTrue(response.steps.all { it.refinement == null })
    }

    @Test
    fun `chain stops when the model needs user input`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        val gen = ScriptedResponseGenerator(
            listOf(GeneratedResponse("a question for you?", taskUpdate = task("planning"), inputTokens = 1, outputTokens = 1, stageComplete = false)),
        )
        val agent = Agent(gen, memory)

        val response = agent.run("hi", history = emptyList(), mode = TransitionMode.AUTO)

        assertEquals(1, gen.calls)
        assertEquals(1, response.steps.size)
        assertNull(response.steps[0].transition)
        assertEquals(TaskState.PLANNING, activeStage(memory))
    }

    @Test
    fun `chain stops stalled after exactly one self-correction that does not fill the artifact`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        val gen = ScriptedResponseGenerator(
            listOf(
                complete("done!", task("planning")), // complete but empty
                complete("still empty", task("planning")), // follow-up STILL empty
            ),
        )
        val agent = Agent(gen, memory)

        val response = agent.run("finish planning", history = emptyList(), mode = TransitionMode.AUTO)

        assertEquals(2, gen.calls, "exactly one follow-up — no loop, no extra stages")
        assertEquals(1, response.steps.size)
        assertEquals(TaskState.PLANNING, response.steps[0].refinement?.stage)
        assertNull(response.steps[0].transition)
        assertEquals(TaskState.PLANNING, activeStage(memory), "stays on the stalled stage")
    }

    @Test
    fun `self-correction within the chain fills the artifact and the chain continues`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        val gen = ScriptedResponseGenerator(
            listOf(
                complete("done!", task("planning")), // planning: complete but empty
                complete("refined", task("planning", req = "- R", dec = "- D")), // follow-up fills it
                GeneratedResponse("need input on execution", taskUpdate = task("execution", req = "- R", dec = "- D"), inputTokens = 1, outputTokens = 1, stageComplete = false),
            ),
        )
        val agent = Agent(gen, memory)

        val response = agent.run("go", history = emptyList(), mode = TransitionMode.AUTO)

        assertEquals(3, gen.calls) // planning + its follow-up + execution
        assertEquals(2, response.steps.size)
        // Step 1: planning self-corrected, then transitioned to execution.
        assertEquals(TaskState.PLANNING, response.steps[0].refinement?.stage)
        assertEquals("refined", response.steps[0].refinement?.replyText)
        assertEquals(TaskState.EXECUTION, response.steps[0].transition?.to)
        // Step 2: execution needs input → no transition, chain stops there.
        assertEquals(TaskState.EXECUTION, response.steps[1].stage)
        assertNull(response.steps[1].transition)
        assertEquals(TaskState.EXECUTION, activeStage(memory))
    }

    @Test
    fun `the chain always terminates at DONE even with a permissive generator`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        // A single response that is complete with ALL sections filled — ready at any stage.
        val gen = ScriptedResponseGenerator(
            listOf(complete("ok", task("planning", req = "- R", dec = "- D", impl = "- I", valid = "- V"))),
        )
        val agent = Agent(gen, memory)

        val response = agent.run("drive it", history = emptyList(), mode = TransitionMode.AUTO)

        // Bounded by genuine progress: PLANNING → EXECUTION → VALIDATION → DONE, then stop.
        assertEquals(3, gen.calls)
        assertEquals(TaskState.DONE, activeStage(memory))
        assertEquals(TaskState.DONE, response.steps.last().transition?.to)
    }

    @Test
    fun `a stale stage line in the model's task_update cannot regress the CODE-owned stage`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        memory.working.setActiveStage("validation")
        // The model returns a STALE stage line (execution) while the chain is on validation,
        // and is not done → a non-advancing iteration (the stop path, no transition).
        val staleUpdate = task("execution", req = "- R", dec = "- D", impl = "- I")
        val gen = ScriptedResponseGenerator(
            listOf(GeneratedResponse("reviewing", taskUpdate = staleUpdate, inputTokens = 1, outputTokens = 1, stageComplete = false)),
        )
        val agent = Agent(gen, memory)

        agent.run("look at it", history = emptyList(), mode = TransitionMode.AUTO)

        assertEquals(1, gen.calls)
        // CODE owns the stage: the model's stale `stage: execution` must not be persisted.
        assertEquals(TaskState.VALIDATION, activeStage(memory))
    }

    @Test
    fun `tokens are summed across every call in the chain`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        val gen = ScriptedResponseGenerator(
            listOf(
                GeneratedResponse("planned", taskUpdate = task("planning", req = "- R", dec = "- D"), inputTokens = 3, outputTokens = 4, stageComplete = true),
                GeneratedResponse("need input", taskUpdate = task("execution", req = "- R", dec = "- D"), inputTokens = 5, outputTokens = 6, stageComplete = false),
            ),
        )
        val agent = Agent(gen, memory)

        val response = agent.run("go", history = emptyList(), mode = TransitionMode.AUTO)

        assertEquals(2, gen.calls)
        assertEquals(8, response.inputTokens)
        assertEquals(10, response.outputTokens)
    }

    @Test
    fun `CONFIRM mode does not auto-advance a ready stage, it defers to next`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        val gen = ScriptedResponseGenerator(
            listOf(complete("planned", task("planning", req = "- R", dec = "- D"))),
        )
        val agent = Agent(gen, memory)

        val response = agent.run("plan it", history = emptyList(), mode = TransitionMode.CONFIRM)

        // Exactly one stage processed; the transition is DEFERRED, not performed.
        assertEquals(1, gen.calls)
        assertEquals(1, response.steps.size)
        assertNull(response.steps[0].transition)
        assertEquals(TaskState.PLANNING, response.steps[0].pendingTransition?.from)
        assertEquals(TaskState.EXECUTION, response.steps[0].pendingTransition?.to)
        // The stage did NOT advance — it waits for `:next`.
        assertEquals(TaskState.PLANNING, activeStage(memory))
        // [3c] Completion is PERSISTED in the header so `:next` works after a restart.
        assertTrue(activeStageComplete(memory))
    }

    @Test
    fun `AUTO advancing resets stage_complete to false on the new stage`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        val gen = ScriptedResponseGenerator(
            listOf(
                complete("planned", task("planning", req = "- R", dec = "- D")),
                // execution needs input → chain stops at execution, not complete
                GeneratedResponse("need input", taskUpdate = task("execution", req = "- R", dec = "- D"), inputTokens = 1, outputTokens = 1, stageComplete = false),
            ),
        )
        val agent = Agent(gen, memory)

        agent.run("go", history = emptyList(), mode = TransitionMode.AUTO)

        // Advanced to execution, and the new stage is not marked complete.
        assertEquals(TaskState.EXECUTION, activeStage(memory))
        assertFalse(activeStageComplete(memory))
    }

    // ── Day 15: model proposes a direction, CODE re-validates ─────────────────────

    @Test
    fun `a forward proposal is performed when it is legal and ready (AUTO)`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        // Planning proposes EXECUTION (forward) with its artifact filled; execution then needs input.
        val gen = ScriptedResponseGenerator(
            listOf(
                proposing("planned", task("planning", req = "- R", dec = "- D"), TaskState.EXECUTION),
                GeneratedResponse("need input", taskUpdate = task("execution", req = "- R", dec = "- D"), inputTokens = 1, outputTokens = 1, stageComplete = false),
            ),
        )
        val agent = Agent(gen, memory)

        val response = agent.run("go", history = emptyList(), mode = TransitionMode.AUTO)

        assertEquals(TaskState.EXECUTION, response.steps[0].transition?.to)
        assertEquals(TaskState.EXECUTION, activeStage(memory))
    }

    @Test
    fun `a backward proposal is performed and the AUTO chain stops at the rework boundary`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        memory.working.setActiveStage("validation")
        // Validation finds blockers → proposes EXECUTION (backward). Even in AUTO, the chain stops
        // after a backward move (no ping-pong), so DONE is NOT reached.
        val gen = ScriptedResponseGenerator(
            listOf(
                proposing("found blockers", task("validation", req = "- R", dec = "- D", impl = "- I", valid = "- gap"), TaskState.EXECUTION),
            ),
        )
        val agent = Agent(gen, memory)

        val response = agent.run("review it", history = emptyList(), mode = TransitionMode.AUTO)

        assertEquals(1, gen.calls, "stops after the single backward move")
        assertEquals(TaskState.EXECUTION, response.steps[0].transition?.to)
        assertEquals(TaskState.EXECUTION, activeStage(memory))
    }

    @Test
    fun `an illegal proposed jump is rejected by code even when the stage is complete (AUTO)`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        // Planning proposes DONE (a skip) with everything filled — code rejects it (no such edge).
        val gen = ScriptedResponseGenerator(
            listOf(proposing("skip ahead", task("planning", req = "- R", dec = "- D", impl = "- I", valid = "- V"), TaskState.DONE)),
        )
        val agent = Agent(gen, memory)

        val response = agent.run("go", history = emptyList(), mode = TransitionMode.AUTO)

        assertEquals(1, gen.calls)
        assertNull(response.steps[0].transition, "an illegal proposal performs no transition")
        assertEquals(TaskState.PLANNING, activeStage(memory), "stays on planning — no fallback to forward")
    }

    @Test
    fun `no final without validation - a forward validation proposal reaches DONE when ready`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        memory.working.setActiveStage("validation")
        // Clean validation (no blockers) proposes DONE (forward).
        val gen = ScriptedResponseGenerator(
            listOf(proposing("all good", task("validation", req = "- R", dec = "- D", impl = "- I", valid = "- all checked"), TaskState.DONE)),
        )
        val agent = Agent(gen, memory)

        val response = agent.run("review it", history = emptyList(), mode = TransitionMode.AUTO)

        assertEquals(TaskState.DONE, response.steps.last().transition?.to)
        assertEquals(TaskState.DONE, activeStage(memory))
    }

    @Test
    fun `CONFIRM persists the proposed direction (incl backward) for next to accept`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        memory.working.setActiveStage("validation")
        val gen = ScriptedResponseGenerator(
            listOf(proposing("rework needed", task("validation", req = "- R", dec = "- D", impl = "- I", valid = "- gap"), TaskState.EXECUTION)),
        )
        val agent = Agent(gen, memory)

        val response = agent.run("review it", history = emptyList(), mode = TransitionMode.CONFIRM)

        // Deferred (not performed), and the backward direction is persisted for `:next`.
        assertNull(response.steps[0].transition)
        assertEquals(TaskState.EXECUTION, response.steps[0].pendingTransition?.to)
        assertEquals(TaskState.VALIDATION, activeStage(memory), "CONFIRM does not advance")
        assertTrue(activeStageComplete(memory))
        assertEquals(TaskState.EXECUTION, activeProposed(memory), "the validated backward target is persisted")
    }

    @Test
    fun `the model cannot set stage_complete via its task_update — CODE owns it`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        // The model embeds stage_complete: true in its markdown, but its SIGNAL is false
        // (needs input). CODE must keep the header false.
        val sneaky = task("planning", complete = "true", req = "- R", dec = "- D")
        val gen = ScriptedResponseGenerator(
            listOf(GeneratedResponse("hmm", taskUpdate = sneaky, inputTokens = 1, outputTokens = 1, stageComplete = false)),
        )
        val agent = Agent(gen, memory)

        agent.run("work", history = emptyList(), mode = TransitionMode.CONFIRM)

        assertFalse(activeStageComplete(memory), "model's embedded stage_complete must be ignored")
        assertEquals(TaskState.PLANNING, activeStage(memory))
    }
}
