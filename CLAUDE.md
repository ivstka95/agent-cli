# Project: CLI Agent (AI Advent, days 11–15)

An interactive command-line agent (Kotlin). The user types messages in a REPL; each goes to the Anthropic Messages API through an encapsulated `Agent`, and the reply prints. This is ONE unified system, built layer by layer across days 11–15: memory layers, personalization, a task state machine, invariants, and controlled state transitions. The goal is to learn to work effectively WITH AI on agent architecture — clean, minimal code over polish, but no bugs.

## ⚠️ READ THE PLAN FIRST — `PLAN.md`
**Before planning or writing any code in a session, read `PLAN.md` in the project root.** It is the single source of truth: the full architecture, the file-storage layout, all locked decisions, the per-day step breakdown (days 11–15), and what is designed-for vs. implemented (e.g. the agent swarm is designed for but NOT implemented yet). This CLAUDE.md is a summary; `PLAN.md` is authoritative and more detailed. If anything here and in `PLAN.md` seem to differ, `PLAN.md` wins.

## What we are building (one system, not 5 programs)
Each day is an ADDITIVE layer feeding a single system-prompt assembly point. Do not build days as separate programs; extend the same system.

- **Day 11 — Memory model:** three separate layers — short-term (session dialog, in-memory), working (current task, files, auto-extracted), long-term (global profile/knowledge, files, manual). Stored separately; explicit control over what goes where; injected into the system prompt (short-term history goes into `messages`).
- **Day 12 — Personalization:** a user profile (style, format, stack, language) on top of long-term memory, attached to every request. Different profiles → different answers.
- **Day 13 — Task State Machine:** stages planning → execution → validation → done. Transitions DEFAULT to AUTO with two-level validation (code + LLM); a CONFIRM mode is optional. Stage persists in the task file; pause/resume across restarts. Designed for a multi-agent swarm but implemented as a SINGLE agent (variant A) behind a `StageController` interface.
- **Day 14 — Invariants:** hard constraints (architecture, tech decisions, stack limits, business rules) stored separately from the dialog, injected into the system prompt FIRST, the assistant refuses to violate them and explains why. On conflict: turn the user away immediately, no replanning loops.
- **Day 15 — Controlled transitions:** explicit allowed transitions; the assistant cannot skip a stage (no planning→done); backward edges for rework (validation→execution). Transition conditions require REAL artifact readiness, not just "agreed".

See `PLAN.md` for the full step-by-step breakdown and all design hooks.

## Tech stack
- **Language:** Kotlin · **Build:** Gradle (Kotlin DSL)
- **HTTP:** Ktor Client (CIO engine)
- **JSON:** kotlinx.serialization (plugin + json lib + ktor content-negotiation)
- **Async:** Coroutines (REPL runs in `runBlocking`)
- **Interface:** interactive REPL (read line → agent → print reply → repeat; `:quit`/`:q` to exit)

## Architecture (clean, layered)
```
agent/      Agent (single system-prompt assembly point), Message, models, LlmClient interface
llm/        AnthropicClient — Ktor (CIO) implementation of LlmClient
memory/     ShortTerm / Working / LongTerm / MemoryStore + ResponseGenerator abstraction   (Day 11)
profile/    Profile                                                                          (Day 12)
task/       TaskState / StagePrompts / TaskStateMachine / Orchestrator / StageController      (Days 13/15)
invariants/ InvariantStore / InvariantChecker                                                (Day 14)
repl/       Repl — interactive loop, owns in-memory session history
Main.kt     entry point: wires everything in runBlocking
```
Packages beyond `agent/`, `llm/`, `repl/` are added as their day arrives. All of them feed the single system-prompt assembly point.

## Core principle: single system-prompt assembly point
`Agent.buildSystemPrompt()` assembles the final system prompt in ONE place. Days 11–15 compose it from layers:
```
final system prompt =
    invariants (must never be violated)        (Day 14)
  + long-term memory (profile + global habits) (Days 12 + 11)
  + working memory (active task context)        (Day 11)
  + current stage prompt                        (Day 13)
short-term memory (history) → messages          (Day 11)
```
Never scatter system-prompt construction. This single point is where every layer plugs in.

## Key design hooks (designed for the maximum, implement the minimum)
- **`ResponseGenerator` interface** — `CombinedResponseGenerator` makes ONE structured-output call returning `{reply, task_update}` (auto-extraction into working memory). Swappable to a separate-call implementation without touching Agent/REPL. On JSON parse failure: fallback to raw reply, don't touch the task, never crash. Keep max_tokens high enough that the JSON isn't truncated.
- **`StageController` interface** — `SingleAgentController` now; `MultiAgentController` (the swarm) later, without changing `Orchestrator`. Swarm is DESIGNED FOR, NOT implemented yet.
- **`allowedTransitions` + `TransitionMode`** — transitions validated by code first (legal edge + artifact present), then LLM (real readiness). DEFAULT AUTO; CONFIRM optional.

## File storage (everything on files; `memory/` at root, gitignored)
```
memory/
  long-term/profile.md      (Day 12, manual)
  long-term/knowledge.md    (Day 11, manual + :remember)
  working/tasks/<name>.md    (Days 11+13, strict structure + stage field, auto-extracted)
  working/active             (pointer to active task)
  invariants.md              (Day 14)
```
Task files use a strict structure (`# Task`, `stage:`, `## Goal/Requirements/Decisions/Done/TODO`). See `PLAN.md`.

## LLM API details
- Provider: **Anthropic**. Endpoint: `https://api.anthropic.com/v1/messages`.
- Headers: `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`.
- Default model: `claude-haiku-4-5-20251001`.
- Request: `model`, `max_tokens`, **top-level `system` field** (NOT a system message inside `messages`), `messages` (array of `{role, content}`).
- Structured output (for the combined call): use Anthropic's structured-output / JSON-schema mechanism; ensure max_tokens covers reply + full task markdown.
- Response: text in `content[0].text`; usage in `usage` (`input_tokens`, `output_tokens`).
- The model is stateless — memory works by composing the system prompt and re-sending history each call.

## API key — STRICT rules
- Read from the `ANTHROPIC_API_KEY` **environment variable** only.
- **NEVER hardcode the key**, never write it to a tracked file, never print it in logs.
- If missing, print a clear error and exit.

## Build & verification
- Build: `./gradlew build`. Run: `./gradlew run`. Tests: `./gradlew test`.
- Build incrementally, layer by layer; the project MUST compile (and tests pass) before moving to the next step.
- Add dependencies only when a layer needs them.

## Conventions
- Idiomatic Kotlin; explicit and readable over clever.
- Resist over-engineering: keep abstractions proportional to what days 11–15 need (the hooks above are the intended exceptions).
- Be concise in explanations: prioritize the plan and code over long prose.
- Per project rules: Tests use hand-written fakes over mocks for core logic.
