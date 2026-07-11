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

### Local LLM as a private HTTP service (Day 30)

Expose the local Ollama model as a small private chat service — a thin Ktor HTTP + HTML
layer over the existing `OllamaLlmClient` (with Day-29's optimized generation params). No
RAG, no auth, in-memory sessions only.

```bash
ollama serve                 # a local model must be running (default: llama3.2)
./gradlew :app:runServer     # binds 0.0.0.0:8080 — open http://localhost:8080
```

Routes: `GET /` serves a self-contained chat page; `POST /chat` `{sessionId?, message}` →
`{sessionId, reply}`. Per-session history lives in memory (cleared on restart) and is
isolated across sessions. Basic limits: a per-IP rate limit (429 when exceeded) and a
max-context cap (history trimmed + input truncated). If Ollama is down, `/chat` returns a
clean error and the server stays up.

Configurable via env vars: `SERVICE_PORT` (8080), `SERVICE_HOST` (0.0.0.0),
`SERVICE_RATE_LIMIT` (20/min), `SERVICE_MAX_HISTORY` (20), `SERVICE_MAX_INPUT_CHARS` (4000),
plus the `OLLAMA_*` vars for the model.

Because it binds `0.0.0.0`, another device on the LAN can reach it at
`http://<this-machine-ip>:8080`. **VPS deploy** (manual, out of code scope): build a
distribution (`./gradlew :app:installDist`), run it on a host with Ollama + a light model,
set `SERVICE_HOST=0.0.0.0`, and open the port in the firewall.

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
