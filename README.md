# agent-cli

An interactive command-line AI agent written in Kotlin. You type messages in a REPL;
each goes to the Anthropic Messages API through an encapsulated `Agent`, and the reply
prints. Built for a learning challenge (AI Advent, days 11–14) — the foundation that
later layers (memory, personalization, a task state machine, invariants) build on.

## Run

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./gradlew run -q
```

Type a message and press Enter; `:quit` or `:q` (or Ctrl-D) exits.

## Architecture

```
agent/  Message, models, LlmClient interface, Agent (single system-prompt assembly point)
llm/    AnthropicClient — Ktor (CIO) implementation of LlmClient
repl/   Repl — interactive loop, owns in-memory session history
Main.kt entry point: wires AnthropicClient -> Agent -> Repl
```

The Agent depends only on the `LlmClient` interface and assembles its system prompt in a
single place (`buildSystemPrompt()`), the extension hook for the upcoming memory /
personalization / state-machine / invariants layers.

## Stack

Kotlin · Gradle (Kotlin DSL) · Ktor Client · kotlinx.serialization · Coroutines.

The API key is read only from the `ANTHROPIC_API_KEY` environment variable — never hardcoded.
