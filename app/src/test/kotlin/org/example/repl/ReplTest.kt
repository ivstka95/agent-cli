package org.example.repl

import kotlinx.coroutines.runBlocking
import org.example.agent.Agent
import org.example.agent.GeneratedResponse
import org.example.agent.Message
import org.example.agent.ResponseGenerator
import org.example.memory.MemoryStore
import org.example.task.TaskHeader
import org.example.task.TaskState
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Scripted generator: returns a queue of responses (clamped to the last), counting calls. */
private class ScriptedResponseGenerator(
    private val responses: List<GeneratedResponse>,
) : ResponseGenerator {
    var calls = 0
        private set

    /** The user input (last message) of each call, in order — so tests can assert what was run. */
    val inputs = mutableListOf<String>()

    override suspend fun generate(
        systemPrompt: String,
        messages: List<Message>,
        currentTask: String?,
    ): GeneratedResponse {
        inputs += messages.last().content
        val response = responses[minOf(calls, responses.lastIndex)]
        calls++
        return response
    }
}

class ReplTest {

    private val root: File = createTempDirectory("repl").toFile()
    private val out = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

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

    private fun replWith(memory: MemoryStore, vararg responses: GeneratedResponse): Repl =
        Repl(Agent(ScriptedResponseGenerator(responses.toList()), memory), memory, out::add)

    private fun replWith(memory: MemoryStore, gen: ScriptedResponseGenerator): Repl =
        Repl(Agent(gen, memory), memory, out::add)

    private fun activeStage(memory: MemoryStore): TaskState =
        TaskHeader.parse(memory.working.activeTaskContent()!!).stage

    private fun printed(substring: String) = out.any { it.contains(substring) }

    // ── :mode ───────────────────────────────────────────────────────────────────

    @Test
    fun `mode defaults to confirm and can be switched`() = runBlocking {
        val memory = MemoryStore(root)
        val repl = replWith(memory, complete("x", task("planning")))

        repl.submit(":mode")
        assertTrue(printed("Transition mode: confirm"), "default mode should be confirm")

        repl.submit(":mode auto")
        assertTrue(printed("Transition mode: auto"))

        repl.submit(":mode confirm")
        assertTrue(printed("Transition mode: confirm."))

        repl.submit(":mode bogus")
        assertTrue(printed("Invalid mode: 'bogus'"))
    }

    // ── CONFIRM (default) end-to-end ──────────────────────────────────────────────

    @Test
    fun `CONFIRM pauses at a ready stage and next advances it`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        val repl = replWith(memory, complete("planned", task("planning", req = "- R", dec = "- D")))

        // A chat turn works planning; it completes + is ready, but CONFIRM does NOT advance.
        repl.submit("work the plan")
        assertEquals(TaskState.PLANNING, activeStage(memory), "CONFIRM must not auto-advance")
        assertTrue(printed("ready to advance to 'execution'"), "should prompt for :next")

        // :next performs the deferred transition.
        repl.submit(":next")
        assertEquals(TaskState.EXECUTION, activeStage(memory))
        assertTrue(printed(">>> Stage transition: planning → execution"))
    }

    @Test
    fun `CONFIRM pauses again at the next boundary, one stage per turn`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        val repl = replWith(
            memory,
            complete("planned", task("planning", req = "- R", dec = "- D")),
            complete("executed", task("execution", req = "- R", dec = "- D", impl = "- I")),
        )

        repl.submit("work the plan") // planning → awaits
        repl.submit(":next") // → execution
        repl.submit("work execution") // execution → awaits (does NOT auto-run validation)

        assertEquals(TaskState.EXECUTION, activeStage(memory))
        assertTrue(printed("ready to advance to 'validation'"))
    }

    // ── AUTO end-to-end ───────────────────────────────────────────────────────────

    @Test
    fun `AUTO mode auto-advances through every stage to DONE in one turn`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        val repl = replWith(
            memory,
            complete("planned", task("planning", req = "- R", dec = "- D")),
            complete("executed", task("execution", req = "- R", dec = "- D", impl = "- I")),
            complete("validated", task("validation", req = "- R", dec = "- D", impl = "- I", valid = "- V")),
        )

        repl.submit(":mode auto")
        repl.submit("drive it")

        assertEquals(TaskState.DONE, activeStage(memory))
        assertTrue(printed(">>> Stage transition: planning → execution"))
        assertTrue(printed(">>> Stage transition: execution → validation"))
        assertTrue(printed(">>> Stage transition: validation → done"))
    }

    // ── :next advance + run (reuses TaskStateMachine readiness) ───────────────────

    /** A task whose CURRENT stage is completed and ready to advance (planning, artifact filled). */
    private fun readyPlanning(memory: MemoryStore) {
        memory.working.createTask("demo")
        memory.working.overwriteActive(task("planning", complete = "true", req = "- R", dec = "- D"))
    }

    @Test
    fun `next with no arg advances and runs the new stage with the service default`() = runBlocking {
        val memory = MemoryStore(root)
        readyPlanning(memory)
        // When run on execution, complete it with a filled Implementation → stops at validation.
        val gen = ScriptedResponseGenerator(listOf(complete("executed", task("execution", impl = "- KeystoreWrapper"))))
        val repl = replWith(memory, gen)

        repl.submit(":next")

        assertTrue(printed(">>> Stage transition: planning → execution"))
        assertEquals(1, gen.calls, "the new stage must be run")
        assertEquals(ReplTest.ADVANCE_INPUT, gen.inputs.last(), "no instruction → neutral service input")
        assertEquals(TaskState.EXECUTION, activeStage(memory))
        assertTrue(printed("ready to advance to 'validation'"), "CONFIRM stops at the next boundary")
    }

    @Test
    fun `next with an instruction runs the new stage with that input`() = runBlocking {
        val memory = MemoryStore(root)
        readyPlanning(memory)
        val gen = ScriptedResponseGenerator(listOf(complete("executed", task("execution", impl = "- X"))))
        val repl = replWith(memory, gen)

        repl.submit(":next focus on the rotation flow")

        assertTrue(printed(">>> Stage transition: planning → execution"))
        assertEquals(1, gen.calls)
        assertEquals("focus on the rotation flow", gen.inputs.last())
        assertEquals(TaskState.EXECUTION, activeStage(memory))
    }

    @Test
    fun `next refuses when the artifact is incomplete and runs nothing`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo") // planning with empty sections
        val gen = ScriptedResponseGenerator(listOf(complete("x", task("planning"))))
        val repl = replWith(memory, gen)

        repl.submit(":next make it so")

        assertEquals(TaskState.PLANNING, activeStage(memory))
        assertTrue(printed("not ready to advance: artifact incomplete"))
        assertEquals(0, gen.calls, "a refused advance runs nothing")
    }

    @Test
    fun `next refuses when the stage is not marked complete and runs nothing`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        // Artifact ready, but stage_complete is false (no chat signalled completion).
        memory.working.overwriteActive(task("planning", req = "- R", dec = "- D"))
        val gen = ScriptedResponseGenerator(listOf(complete("x", task("planning"))))
        val repl = replWith(memory, gen)

        repl.submit(":next")

        assertEquals(TaskState.PLANNING, activeStage(memory))
        assertTrue(printed("not ready to advance: stage not marked complete"))
        assertEquals(0, gen.calls)
    }

    @Test
    fun `next at DONE reports already complete and runs nothing`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        memory.working.setActiveStage("done")
        val gen = ScriptedResponseGenerator(listOf(complete("x", task("done"))))
        val repl = replWith(memory, gen)

        repl.submit(":next")

        assertEquals(TaskState.DONE, activeStage(memory))
        assertTrue(printed("already complete"))
        assertEquals(0, gen.calls)
    }

    @Test
    fun `next advancing INTO done prints the transition but runs no agent turn`() = runBlocking {
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        // validation completed + ready → :next advances into the terminal DONE stage.
        memory.working.overwriteActive(
            task("validation", complete = "true", req = "- R", dec = "- D", impl = "- I", valid = "- V"),
        )
        val gen = ScriptedResponseGenerator(listOf(complete("x", task("validation"))))
        val repl = replWith(memory, gen)

        repl.submit(":next")

        assertEquals(TaskState.DONE, activeStage(memory))
        assertTrue(printed(">>> Stage transition: validation → done"))
        assertEquals(0, gen.calls, "DONE is terminal — no agent turn")
    }

    @Test
    fun `next advances a stage completed in a previous session and runs the new stage (restart)`() = runBlocking {
        // Simulate a restart: a task persisted as complete last session, artifact filled, no chat yet.
        val memory = MemoryStore(root)
        memory.working.createTask("demo")
        memory.working.overwriteActive(
            task("execution", complete = "true", req = "- R", dec = "- D", impl = "- KeystoreWrapper"),
        )
        // Running validation needs input → chain stops there.
        val gen = ScriptedResponseGenerator(
            listOf(GeneratedResponse("a question", taskUpdate = task("validation"), inputTokens = 1, outputTokens = 1, stageComplete = false)),
        )
        val repl = replWith(memory, gen)

        repl.submit(":next")

        // Readiness read straight from the file → advances even with no chat this session, then runs.
        assertEquals(TaskState.VALIDATION, activeStage(memory))
        assertTrue(printed(">>> Stage transition: execution → validation"))
        assertEquals(1, gen.calls, "the new stage runs after the restart-driven advance")
        assertEquals(ReplTest.ADVANCE_INPUT, gen.inputs.last())
    }

    private companion object {
        const val ADVANCE_INPUT = "Proceed with the current stage."
    }
}
