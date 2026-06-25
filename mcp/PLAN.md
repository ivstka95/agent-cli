# Implementation Plan — Day 16: Connect to MCP (`mcp/` module)

This is the single source of truth for the **Day 16** layer of the AI Advent challenge. It is a
**new, autonomous Gradle module** (`mcp/`) inside the `agent-cli` repo. The Days 11–15 agent is
documented in the root [`PLAN.md`](../PLAN.md); this module is separate and decoupled for now,
living in the same repo so it can integrate with the agent in **Day 18**.

> **Status:** plan written, **NOT yet implemented**. The steps below are executed in a later
> build session. Read this file before writing any code in `mcp/`.

---

## Goal

Minimal code that **connects to an existing MCP server over stdio and retrieves the list of
available tools**. We connect to the reference server `@modelcontextprotocol/server-everything`
launched locally via `npx`. We do **NOT** write our own MCP server yet (a later day).

**Verify:** (a) the connection establishes successfully; (b) `listTools()` returns the tool list
and it is printed correctly (non-empty, with tool names and descriptions).

---

## Scope guard

**IN scope (Day 16):**
- A new `mcp/` Gradle module.
- An `McpClient` wrapper with `connect()` + `listTools()`.
- The stdio transport created **behind a factory** (abstraction, not hardwired).
- A **configurable** server launch command (config value, not hardwired).
- Print the tool list (names + descriptions).
- Manual run + one (ideally) automated test.

**OUT of scope (designed-for, NOT built now):**
- `callTool()` — Day 17. Leave a clear, documented seat for it in `McpClient`.
- HTTP/SSE transport (remote VPS-hosted server) — Day 18+. The factory makes this a
  **one-place change**.
- Integration into the Days 11–15 agent — Day 18.
- Writing our own MCP server — later.

**Design principle:** interface with headroom, minimal implementation now. Each upcoming day
builds on this and gets harder, so the scalability hooks (transport factory, configurable server
command, `callTool` seat) are explicit and non-negotiable — but nothing beyond Day 16's goal is
implemented.

---

## Environment & SDK (verified 2026-06-22)

- **Node/npx:** present (node `v25.9.0`, npx `11.x`). `npx -y @modelcontextprotocol/server-everything`
  runs the reference server over stdio.
- **SDK:** `io.modelcontextprotocol:kotlin-sdk:0.13.0` — latest release on Maven Central
  (released 2026-06-02) and GitHub. **Umbrella artifact** — client + server APIs in one. The
  client is used now (Day 16); the server side is available later (Day 18) without changing the
  dependency.
- **Repo baseline:** Gradle 8.12, Kotlin 2.0.21, Java 21 toolchain, version catalog at
  `gradle/libs.versions.toml`, package root `org.example`.

### SDK client API used (0.13.0)
```kotlin
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.Implementation
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

val client = Client(clientInfo = Implementation(name = "agent-cli-mcp", version = "0.1.0"))

val process = ProcessBuilder(config.command)
    .redirectError(ProcessBuilder.Redirect.INHERIT)
    .start()

val transport = StdioClientTransport(
    input  = process.inputStream.asSource().buffered(),
    output = process.outputStream.asSink().buffered(),
)

client.connect(transport)            // suspend — performs the MCP init handshake
val result = client.listTools()      // suspend — ListToolsResult
result.tools.forEach { println("${it.name}: ${it.description}") }
```
- `client.connect(...)` and `client.listTools()` are `suspend` → call inside `runBlocking`.
- Transitive deps (Ktor, kotlinx.io, coroutines, serialization) arrive via the SDK. A stdio
  transport needs **no** CIO-style HTTP engine.

---

## Architecture / module layout

```
mcp/
  PLAN.md                          (this file)
  build.gradle.kts
  src/main/kotlin/org/example/mcp/
    Main.kt                        // demo entry: config → factory → client → listTools → print
    McpClient.kt                   // wrapper: interface + impl — connect(), listTools() [callTool seat]
    config/
      ServerConfig.kt              // configurable launch command (command list)
    transport/
      McpTransportFactory.kt       // factory abstraction → returns a connected-ready transport (+ handle)
      StdioTransportFactory.kt     // stdio impl: ProcessBuilder + StdioClientTransport
  src/test/kotlin/org/example/mcp/
    McpClientStdioTest.kt          // connect + listTools against real server-everything (see verification)
```

- Package root: `org.example.mcp` (consistent with the existing `org.example`).
- **Decoupled from the agent:** `mcp` does **not** depend on `:app`, and `:app` does **not**
  depend on `mcp`. Integration is deferred to Day 18.
- **Transport-agnostic client:** everything after `connect()` (i.e. `listTools()`) is identical
  regardless of which transport the factory produced.

---

## Locked decisions

1. **Structure** — new Gradle module `mcp/` in the same repo; autonomous for now.
   Wiring: `include("app", "mcp")` in `settings.gradle.kts`; new `mcp/build.gradle.kts` using the
   version catalog, Kotlin 2.0.21, Java 21, the `application` plugin, main class
   `org.example.mcp.MainKt`.

2. **SDK** — `io.modelcontextprotocol:kotlin-sdk:0.13.0`, the umbrella artifact (client now,
   server later). Add to `gradle/libs.versions.toml`:
   - `[versions] mcp = "0.13.0"`
   - `[libraries] mcp-kotlin-sdk = { module = "io.modelcontextprotocol:kotlin-sdk", version.ref = "mcp" }`

3. **Transport behind a factory (scalability hook).** `McpTransportFactory` is an abstraction that
   creates the transport; `StdioTransportFactory` is the only implementation now. The client logic
   after `connect()` is transport-agnostic and **identical** regardless of transport. A future
   HTTP/SSE transport (Day 18+) slots in by adding one factory implementation and changing **one
   place** — which factory is constructed. Nothing else changes.

4. **Configurable server launch command (scalability hook).** `ServerConfig` holds the launch
   command (default `listOf("npx", "-y", "@modelcontextprotocol/server-everything")`) and is passed
   into the stdio factory. Switching to `server-filesystem`, `server-fetch`, or our own server is a
   **config change, not a code change**. The command must never be hardwired inside the client or
   factory logic.

5. **Scope = list tools only.** Implement `connect()` + `listTools()` + print. `McpClient` carries
   a clear, documented seat for a future `callTool(name, args)` (Day 17) so it slots in without
   rework — e.g. a commented signature / TODO marking exactly where it goes and that it reuses the
   same connected `Client`. Do not implement it now.

6. **Client wrapper.** A small `McpClient` abstraction wrapping the SDK `Client`. Methods
   `connect()` and `listTools()` now; transport supplied by the factory; server command from
   config; transport- and server-agnostic. `connect()` asks the factory for a transport, then calls
   the SDK `client.connect(...)`. Provide a `close()`/shutdown path that closes the client/transport
   and terminates the server subprocess cleanly.

---

## Implementation steps (later build session)

1. `settings.gradle.kts`: change `include("app")` → `include("app", "mcp")`.
2. `gradle/libs.versions.toml`: add the `mcp` version and `mcp-kotlin-sdk` library entries (above).
3. `mcp/build.gradle.kts`: `kotlin-jvm` + `kotlin-serialization`(if needed) + `application` plugins;
   Java 21 toolchain; deps = `libs.mcp.kotlin.sdk`, `libs.kotlinx.coroutines.core`, and
   `libs.junit.jupiter.engine` (test); `application { mainClass = "org.example.mcp.MainKt" }`.
4. `config/ServerConfig.kt`: `data class ServerConfig(val command: List<String>)` with a default
   factory for `server-everything`.
5. `transport/McpTransportFactory.kt`: an interface returning a transport plus a process/close
   handle. `transport/StdioTransportFactory.kt`: builds `ProcessBuilder(config.command)
   .redirectError(INHERIT).start()` and wraps the streams into `StdioClientTransport`.
6. `McpClient.kt`: interface (`connect()`, `listTools()`, documented `callTool()` seat, `close()`)
   + impl wrapping `Client(Implementation("agent-cli-mcp", "0.1.0"))`, using the injected factory.
7. `Main.kt`: `runBlocking { build ServerConfig → StdioTransportFactory → McpClient → connect()
   → listTools() → print name + description for each tool → close() }`.

Build incrementally; the module must compile and tests pass before this layer is considered done.

---

## Verification plan

**(a) Connection establishes.** `./gradlew :mcp:run` connects to `server-everything` over stdio
without error; after the handshake, print the server name/version.

**(b) Tool list returns & prints correctly.** Output is **non-empty** and lists each tool's
**name + description** (server-everything exposes several, e.g. `echo`, `add`, …).

**Test (ideally).** One JUnit 5 test (`McpClientStdioTest`) that launches the real server via the
stdio factory, connects, calls `listTools()`, and asserts the list is non-empty and contains an
expected tool name. It requires Node at test time — skip gracefully (e.g. assume/`Assumptions`) if
`npx` is unavailable on the runner. Keep it minimal.

**Build green.** `./gradlew :mcp:build` compiles and tests pass; `./gradlew build` (whole repo)
remains green.

---

# Day 17 — First MCP tool: our GitHub MCP server + `callTool` (IMPLEMENTED)

Day 17 turns `:mcp` from a client-only module into **client + server**, and the agent (`:app`) uses
a tool for the first time. This section covers the `:mcp` side (server + `callTool`); the agent-side
agentic loop and the `:app → :mcp` coupling are noted in the root [`PLAN.md`](../PLAN.md).

## What was built

**Our GitHub MCP server** (`server/` package) wrapping the **public** GitHub REST API — one tool,
no token (60 req/hr unauthenticated):
- `get_recent_commits(owner, repo, limit?)` → recent commits (message subject, author, date) from
  `GET /repos/{owner}/{repo}/commits?per_page={limit}` (`limit` default 10, clamp 1..30). Handler
  never throws out — HTTP/404/rate-limit/bad-args become `CallToolResult.error(...)`.
- Layout: `server/github/` (`GitHubClient` + `@Serializable` DTOs, injectable Ktor engine for tests),
  `server/tools/` (`McpToolDefinition` + `McpToolRegistry` + `GetRecentCommitsTool`),
  `server/transport/` (factory + `HttpServerTransportFactory` + `StdioServerTransportFactory`),
  `server/config/ServerBindConfig`, `server/GitHubMcpServer`, `server/ServerMain`.

**`callTool` — the Day-16 seat, filled.** `McpClient.callTool(name, arguments: Map<String, Any?>):
CallToolResult` delegates to the SDK `client.callTool(...)`, reusing the SAME connected `Client`.
Helper `CallToolResult.textOrError()` extracts text without touching content-block polymorphism.

**HTTP transport, both sides** (HTTP primary, stdio fallback):
- Server: `HttpServerTransportFactory` runs `embeddedServer(CIO){ mcp { GitHubMcpServer.build(github) } }`
  — the SDK's `Application.mcp` installs SSE + content-negotiation and mounts the SSE GET + POST at
  the root path. `StdioServerTransportFactory` is the documented fallback (mirrors the client-side
  factory discipline; not wired into `ServerMain`).
- Client: `HttpClientTransportFactory` builds a Ktor `HttpClient(CIO){ install(SSE) }` +
  `SseClientTransport(client, urlString = serverUrl)` — the Day-17 counterpart of `StdioTransportFactory`.
- `ServerBindConfig` (default `127.0.0.1:3001`, overridable via `MCP_HOST`/`MCP_PORT`): a VPS deploy
  later is a config change, not a code change.

## Locked decisions / notes

1. **`:mcp` primary run target → `org.example.mcp.server.ServerMainKt`** (`./gradlew :mcp:run` starts
   the HTTP server). The Day-16 stdio client demo stays runnable: `./gradlew :mcp:runClientDemo`.
2. **Extensible tool registry (Day-18 hook).** Adding a tool = one `McpToolDefinition` appended to
   `McpToolRegistry.default(...)`; `registerAll` and `GitHubMcpServer.build` are untouched.
3. **`:mcp` exposes the SDK with `api` (not `implementation`)** + the `java-library` plugin —
   `McpClient`'s methods return SDK types (`Tool`, `CallToolResult`), so `:app` must see them.
4. **⚠️ Ktor 3.4.3 (project-wide).** kotlin-sdk 0.13.0 forces Ktor 3.x transitively; Day 16 dodged it
   (stdio uses no engine) but Day 17's `:app → :mcp` + SSE made it real, so the whole project was
   bumped 2.3.13 → 3.4.3 (`:app`'s `AnthropicClient` needed no source change; only `slf4j.nop` →
   `slf4j2.nop`). Note: `ktor-client-sse` is NOT a separate artifact in 3.x — the client SSE plugin
   ships in `ktor-client-core`.

## Verification

- `GetRecentCommitsToolTest` — handler with Ktor `MockEngine` (GitHub stubbed): success formats
  commits; 404 → error result; missing args → error result (no HTTP call).
- `CallToolHttpTest` — embedded HTTP server in-process (GitHub stubbed) + a real MCP client over SSE:
  `listTools()` advertises the tool and `callTool(...)` returns the commits.
- Whole-repo `./gradlew build` green (Days 11–16 not regressed).

## OUT of scope (Day 18, designed-for not built)
Scheduler/periodic execution, persistence (JSON/SQLite), 24/7 background, physical VPS deploy. The
registry + HTTP transport leave the hooks; no Day-18 machinery is built.

---

# Day 19 — MCP tool composition: a 3-tool pipeline on our server (PLANNED)

> **Status:** plan written, **NOT yet implemented**. Read this section before writing any Day-19 code
> in `mcp/`. The agent-side note is in the root [`PLAN.md`](../PLAN.md) (Day 19) — but **expect no
> agent code changes**; all new code is here on the server.

## Goal

Prove the LLM **composes a chain of MCP tools**: from a single natural-language request it calls
*get data → process → save* in sequence, **passing data between the tools**, with **no hardcoded
sequence** in the agent. This largely **reuses the Day-17 infrastructure** (extensible registry +
`AgenticLoop`); the only new code is **two deterministic tools** on our server.

Three constraints (clarified with the course author) shape every decision:
1. All three tools live **on our one MCP server** — not local agent tools, not three servers.
2. The server stays a **thin adapter — NO LLM on the server**. "Process/summarize" is **deterministic**
   (a report/итог, not LLM summarization). No API key, no `AnthropicClient` server-side.
3. The **LLM decides and orders** the chain via the existing agentic loop.

---

## Scope guard

**IN scope (Day 19):**
- Two NEW deterministic MCP tools, registered via the existing `McpToolRegistry`:
  - `build_commit_report(commits)` — groups / counts / themes the commit text into a report. No LLM.
  - `save_to_file(filename, content)` — writes `content` to a protected, sanitized path on the server
    side; returns the saved path. No path traversal.
- Reuse `get_recent_commits(owner, repo, limit?)` **unchanged** as the "get" stage.
- A small **shared `[MCP SERVER]` logging helper** so the new tools log like the Day-17 tool.
- A **gitignored** server output directory.
- Unit tests for both new tools; a manual E2E for the full LLM-driven chain.

**OUT of scope (do NOT build):**
- Any LLM / `AnthropicClient` / API key on the server. The report is pure string processing.
- Any new MCP server, transport, or registry architecture — the Day-17 registry already does
  multi-tool; we append definitions.
- Any **hardcoded tool sequence** in the agent (the LLM must choose the order).
- Changes to interactive/digest modes beyond what the new tools require (ideally zero).
- Changing `get_recent_commits`'s contract or output format (the report parses today's format).
- Anything from future days (parallel tool calls, conditional routing, …).

**Design principle:** new work is proportional to the goal — two pure functions over data + file IO.
The registry and loop are already general; we plug in, we don't re-architect.

---

## Architecture — what plugs in where

```
USER (conversational mode):
  "Get the latest commits for JetBrains/kotlin, build a report, and save it to kotlin-report.md"
        │
        ▼
AGENT  AgenticLoop.run()  ── UNCHANGED ──  repeat(maxIterations = 5):
        │   turn 1: LLM → tool_use get_recent_commits(owner, repo, limit?)
        │   turn 2: LLM (sees commit text) → tool_use build_commit_report(commits=<that text>)
        │   turn 3: LLM (sees report text) → tool_use save_to_file(filename, content=<the report>)
        │   turn 4: LLM → final answer ("saved to …")
        ▼ each tool_use executed via mcpClient.callTool(...); result fed back as a tool_result block
SERVER  GitHubMcpServer → McpToolRegistry.default(github, outputDir).registerAll(server)
        ├── get_recent_commits  (reused, unchanged)     server/tools/GetRecentCommitsTool.kt
        ├── build_commit_report (NEW, pure)             server/tools/BuildCommitReportTool.kt
        └── save_to_file        (NEW, file IO+sanitize) server/tools/SaveToFileTool.kt
```

**Data passing is THE verification point.** Data flows tool → LLM context → next tool's arguments.
`AnthropicClient.runToolTurn` replays each tool result as a `user` `tool_result` block, so the LLM
forms `build_commit_report`'s `commits` arg from stage-1 output and `save_to_file`'s `content` arg
from stage-2 output. **Nothing is wired in code — the LLM threads it.**

---

## Locked decisions

### 1. `build_commit_report` receives the commit TEXT as an argument (data through the LLM)
Two options were on the table:
- **(A, CHOSEN)** `build_commit_report(commits: string)` — the LLM passes the **full text output of
  `get_recent_commits`** as the `commits` argument; the tool parses our own fixed line format and
  emits a report.
- (B, rejected) the tool re-fetches from GitHub given `owner/repo/limit`.

**Why A:** Day 19's entire point is *"correct data passing between tools."* Option B passes **no data
between tools** (each tool independently hits GitHub), so the chain proves nothing and duplicates the
fetch. Option A makes stage-1 output literally become stage-2 input through the LLM — exactly what we
must demonstrate.

**Data-passing implication / coupling:** the report parses the **same line format**
`GetRecentCommitsTool.format()` emits — a header line then `- <sha7> <message> — <author> (<date>)`.
Both tools are ours, so the coupling is intentional. The parser must be **lenient**: skip the header
and any unparseable line (e.g. `No commits found …`), **never throw**, and if it parses **zero**
commits return `CallToolResult.error("no parseable commits in input")` so the model can react. We do
**NOT** change `get_recent_commits` to emit JSON — it's locked unchanged; parsing our own
deterministic text is fine.

**Report content (deterministic, no LLM):**
- Total commit count.
- **Commits grouped by author** with per-author counts, sorted descending.
- **Most active author** (top group; deterministic tie-break: highest count, then author name asc).
- **Common themes** by keyword: count conventional-commit type prefixes in messages (`feat`, `fix`,
  `docs`, `chore`, `refactor`, `test`, `ci`, `build`, `perf`, `style`) and the top-N most frequent
  non-trivial words (lowercased, short/stop-words removed). Pure counting.
- Returned as a **formatted text report** via `CallToolResult.success(report)`.

Input schema: `{ commits: string (required) }`.

### 2. `save_to_file(filename, content)` — protected, sanitized, server-side write
- Input schema: `{ filename: string (required), content: string (required) }`.
- **Fixed base directory**, resolved once at server start: default `mcp/out/` (under the `:mcp`
  module), overridable via env `MCP_OUTPUT_DIR` (mirrors the `ServerBindConfig` env-override style).
  **Injected** into the tool (constructor arg), never hardwired in the handler.
- **Filename sanitization (no path traversal):** strip any directory component (normalize/reject `/`,
  `\`, `..`), allow a safe charset only (e.g. `[A-Za-z0-9._-]`), collapse the rest, enforce a max
  length, default a name if empty. Resolve against the base dir and **verify the canonical resolved
  path is still inside the base dir**; if not → `CallToolResult.error(...)`. Never write outside base.
- Create the base dir if missing; write `content` UTF-8 (overwrite). Return
  `CallToolResult.success("Saved <bytes> bytes to <relative path>")` — the path/confirmation, not the
  content.
- **Gitignore** the output dir (add `mcp/out/` to `.gitignore`).

### 3. Registry stays the only wiring point (Day-17 hook)
Add `BuildCommitReportTool` + `SaveToFileTool` and append their `.definition()`s in
`McpToolRegistry.default(...)`. `build_commit_report` needs no dependency (pure); `save_to_file` needs
the output base dir, so `default(...)` gains an `outputDir` parameter (resolved in
`GitHubMcpServer.build` / `ServerMain` from `MCP_OUTPUT_DIR`). `registerAll` and the SSE/transport
wiring are **untouched**.

### 4. No agentic-loop changes
The loop already chains and feeds results back. A 3-tool chain + final answer = **4 turns ≤
`DEFAULT_MAX_ITERATIONS = 5`** → **no change required**. *Contingency only:* if the model wastes a
turn and trips the guard during E2E, the single change considered is bumping that constant — do not
pre-emptively change it.

### 5. The full report must reach the file intact (the key E2E risk)
`save_to_file`'s `content` must carry the **entire** `build_commit_report` output verbatim — the LLM
must not paraphrase or truncate. **Mitigation:** `save_to_file`'s description explicitly instructs the
model to pass the **exact, full content** to save (no summarizing). **Verify in E2E** by diffing the
file against the `build_commit_report` result shown in the `[MCP SERVER]` log.

### 6. Server stays thin / LLM-free
No `Anthropic` / API key under `server/` (confirmed). Both new tools are pure functions over data +
file IO. **No new dependency** is added to `:mcp` — kotlinx.serialization + std lib + the SDK cover
everything; file IO uses `java.nio` / `java.io`.

### 7. Shared `[MCP SERVER]` logging
Today the cyan `[MCP SERVER]` helper is **private inside `GetRecentCommitsTool`**. Extract a tiny
shared helper (e.g. `server/McpServerLog.kt` over the existing `Ansi`) and route all three tools'
tool-call + result logs through it, so the new tools appear in the colored log exactly like the
Day-17 tool. Minimal refactor; no change to the existing tool's output.

---

## Files

**New (server):**
- `server/tools/BuildCommitReportTool.kt`
- `server/tools/SaveToFileTool.kt`
- `server/McpServerLog.kt` (shared cyan logger; recommended)

**Modified (server):**
- `server/tools/McpToolRegistry.kt` — register two tools; add `outputDir` param to `default(...)`.
- `server/GitHubMcpServer.kt` + `server/ServerMain.kt` — resolve output dir (default `mcp/out/`,
  `MCP_OUTPUT_DIR` override), thread it into the registry, print the two new tools in the banner.
- `server/tools/GetRecentCommitsTool.kt` — switch its private `log`/`logResult` to the shared helper
  (no output change).
- `.gitignore` — add `mcp/out/`.

**New (tests):**
- `src/test/kotlin/org/example/mcp/server/BuildCommitReportToolTest.kt`
- `src/test/kotlin/org/example/mcp/server/SaveToFileToolTest.kt`

**Agent side:** expected **no code changes** — the existing loop already advertises every server tool
and chains them. Confirmed in E2E only.

---

## Verification

**Unit tests (JUnit 5, hand-written inputs — no network, no LLM):**
- `BuildCommitReportToolTest`:
  - Given a known multi-author commit block (in `get_recent_commits` format), the report is
    **deterministic**: exact total, per-author counts, correct most-active author (incl. a tie-break
    case), and theme counts for known prefixes/keywords.
  - Header line and an unparseable / `No commits found` line are skipped gracefully.
  - Zero parseable commits → `CallToolResult.error(...)` (not a crash).
- `SaveToFileToolTest`:
  - Writes the file under the base dir and returns the saved path; file content == input `content`.
  - **Sanitizes** a messy filename (spaces/odd chars) to a safe name.
  - **Rejects path traversal**: `../escape.md`, `/etc/passwd`, `a/b/c.md` all stay inside (or error);
    assert nothing is ever written outside the base dir.

**E2E (manual, the headline check):**
1. Start the server: `./gradlew :mcp:run` (HTTP/SSE on `127.0.0.1:3001`).
2. Run the agent conversational mode with `ANTHROPIC_API_KEY` set; ask one request requiring the
   chain, e.g. *"Get the latest commits for JetBrains/kotlin, build a report, and save it to
   kotlin-report.md."*
3. Confirm the **colored logs** show, in order, three `[MCP SERVER]` tool calls —
   `get_recent_commits`, then `build_commit_report`, then `save_to_file` — each with its result (and
   the matching `[AGENT]` lines). This proves the **LLM** composed the chain (not us).
4. Confirm a file appears in `mcp/out/` and its **content matches the `build_commit_report` output**
   from the log (full report passed intact through the LLM — decision #5).
5. **No regression:** `./gradlew build` green (Days 11–18 unaffected); interactive + digest modes
   still work.
