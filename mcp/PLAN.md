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
