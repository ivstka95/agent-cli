# Project: CLI Agent (AI Advent, days 11–14)

An interactive command-line agent (Kotlin) built for a learning challenge. The user types messages in a REPL; each goes to the Anthropic API through an encapsulated Agent, and the reply prints. This is the foundation that days 11–14 build on: memory layers, personalization, a task state machine, and invariants. The goal is to learn to work effectively WITH AI on agent architecture — clean, minimal code over polish, but no bugs.

## Scope by challenge day
- **Day 11 — Memory model:** three separate memory layers — short-term (current dialog/session), working (current task data / TaskContext), long-term (global profile/decisions/knowledge across all tasks). Stored separately; explicit control over what goes where; injected into the system prompt.
- **Day 12 — Personalization:** a user profile (style, format, stack, language preferences) stored in long-term memory and attached to every request. Different profiles produce different answers.
- **Day 13 — Task State Machine:** task progresses through stages (planning → execution → validation → done) with AUTOMATIC transitions (the LLM decides a stage is complete). Recommended: multiple agents, one per stage, each with its own system prompt, plus an orchestrator. Pause at any stage; resume without re-explaining (persist state).
- **Day 14 — Invariants:** hard constraints the assistant must never violate (architecture, tech decisions, stack limits, business rules). Stored separately from the dialog; injected into the system prompt; the assistant refuses requests that violate them and explains why.

Build the foundation first; layer days 11–14 on top incrementally.

## Tech stack
- **Language:** Kotlin
- **Build:** Gradle (Kotlin DSL)
- **HTTP:** Ktor Client (CIO engine)
- **JSON:** kotlinx.serialization (plugin + json lib + ktor content-negotiation)
- **Async:** Coroutines (REPL runs in `runBlocking`)
- **Interface:** interactive REPL (read line → call agent → print reply → repeat; `:quit` to exit)

## Architecture (clean, layered — matters for later days)
```
agent/
  Agent.kt        — core agent: encapsulates request→response. Holds a systemPrompt
                    and a `suspend fun run(userInput, history): AgentResponse`.
                    Depends ONLY on the LlmClient interface. The system prompt is
                    assembled at a SINGLE point (see principle below).
  LlmClient.kt    — interface: suspend fun complete(systemPrompt, messages): LlmResult
  Message.kt      — domain model: role (USER/ASSISTANT enum) + content
  models.kt       — LlmResult (reply text + token usage), AgentResponse
llm/
  AnthropicClient.kt — implements LlmClient via Ktor. POST /v1/messages,
                       top-level `system` field, @Serializable DTOs.
repl/
  Repl.kt         — interactive loop; holds in-memory session history.
Main.kt           — entry point: wire AnthropicClient -> Agent -> Repl in runBlocking.
```
Later days add packages (e.g. `memory/`, `task/`, `invariants/`) that feed into the single system-prompt assembly point.

## Core principle: single system-prompt assembly point
The Agent must assemble its final system prompt in ONE clearly-marked place. Days 11–14 compose it from layers:
```
final system prompt =
    invariants (must never be violated)
  + long-term memory (profile + global habits)
  + working memory (current task context)
  + current stage prompt (planning / execution / ...)
short-term memory (history) goes into the messages array as usual.
```
Do not scatter system-prompt construction across the code. This single point is where everything plugs in.

## LLM API details
- Provider: **Anthropic**. Endpoint: `https://api.anthropic.com/v1/messages`.
- Headers: `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`.
- Default model: `claude-haiku-4-5-20251001`.
- Request: `model`, `max_tokens`, **top-level `system` field** (NOT a system message inside `messages` — Anthropic uses a separate `system` parameter), and `messages` (array of `{role, content}`).
- Response: assistant text in `content[0].text`; token usage in `usage` (`input_tokens`, `output_tokens`).
- Memory works by composing the system prompt and re-sending history each call — the model is stateless.

## API key — STRICT rules
- The key is read from the `ANTHROPIC_API_KEY` **environment variable** only.
- **NEVER hardcode the key**, never write it to a tracked file, never print it in logs.
- If the env var is missing, print a clear error and exit.

## Build & verification
- Build with `./gradlew build`; run with `./gradlew run`.
- Build incrementally, layer by layer; the project MUST compile before moving to the next layer.
- Add dependencies only when a layer needs them.

## Conventions
- Idiomatic Kotlin; explicit and readable over clever.
- Resist over-engineering: keep abstractions proportional to what days 11–14 need.
- Be concise in explanations: prioritize the plan and code over long prose.
