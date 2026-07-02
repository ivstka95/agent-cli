# Implementation Plan — Day 21: Document indexing (`rag/` module)

This is the module-level plan for the `:rag` module (RAG week, Days 21–22+). The root
[`PLAN.md`](../PLAN.md) carries a short pointer to this file; **this file is authoritative** for
everything RAG-side. Structured like [`mcp/PLAN.md`](../mcp/PLAN.md).

## Context — RAG week starts here
Day 21 opens **RAG week**, a new topic separate from the MCP work of Days 16–20. The distinction:

- **MCP** wires **external tools** — the model calls out to servers that *do* things (GitHub, files).
- **RAG** works with **internal knowledge** — documents are embedded into vectors, searched by
  similarity, and the top matches are loaded into the model's context *at inference time*.

Day 21 builds only the **indexing pipeline** (the foundation): documents → chunking → embeddings →
a saved index with metadata. Nothing is queried yet. Day 22 adds retrieval + an LLM answer; later
days add reranking and integrate RAG into the agent so it can answer questions about its **own
codebase**.

---

## Goal
Index our own repository so it can later be queried. Load repo docs + `.kt` sources → chunk with
**two comparable strategies** → embed each chunk via **Ollama (`nomic-embed-text`, 768-dim)** →
**normalize** → store in a **JSON vector index** with per-chunk **metadata**. Run both strategies
and print a comparison (chunk counts / sizes / differences). All IO sits behind three abstractions —
`Embedder`, `VectorIndex`, `ChunkingStrategy` — so Day 22 and later days plug in without touching
this code.

## Scope guard
**IN (Day 21):**
- the `:rag` Gradle module (mirrors `:mcp`; `:app`-dependable later, autonomous now);
- `Embedder` + `OllamaEmbedder` (HTTP to local Ollama);
- `VectorIndex` + `JsonVectorIndex` — cosine similarity, **normalized** vectors, JSON on disk;
- two `ChunkingStrategy` impls (`FixedSizeChunking`, `StructuralChunking`) + their comparison;
- repo document loading; per-chunk metadata; the indexing entry point (`Main.kt`);
- unit tests: chunking deterministic, cosine/normalize math, index round-trip — all with a
  **fake embedder** (no live Ollama in tests).

**OUT (future days — leave the door open, do NOT build):**
- **RAG query / retrieval + LLM answer — Day 22.** `VectorIndex.search(queryVector, topK)` already
  seats it.
- **Reranking — later day.** `search` returning *ranked* candidates already seats it.
- **Integrating RAG into the agent — later day.** `:rag` being `:app`-dependable (mirroring the
  existing `:app → :mcp` edge) already seats it; the edge is added then, never reversed.
- **No SQLite/FAISS** (JSON now; the `VectorIndex` abstraction seats a swap — FAISS isn't a Kotlin
  option anyway). **No cloud embedder** (Ollama now; the `Embedder` abstraction seats a swap).
  Nothing model-specific.
- **Do not touch `:app` / `:mcp` / digest.**

## Environment & config (verified 2026-07-01)
- **Ollama** installed and running locally: model `nomic-embed-text`, **768-dim**, at
  `http://localhost:11434`. Endpoint `POST /api/embeddings`.
- **Reused stack** (already in `gradle/libs.versions.toml`, used by `:app`'s `AnthropicClient`):
  Ktor 3.4.3 client (core/cio/content-negotiation) + `ktor-serialization-kotlinx-json`,
  kotlinx.serialization-json 1.7.3, coroutines 1.9.0, Kotlin 2.3.21, JUnit 5.11.1.

`RagConfig` — a `data class` with a `companion fun fromEnv()`, following the `ServerBindConfig`
idiom (`System.getenv(...)?.takeIf { it.isNotBlank() } ?: DEFAULT`):

| Env var | Default | Purpose |
|---|---|---|
| `OLLAMA_HOST` | `http://localhost:11434` | Ollama base URL |
| `OLLAMA_EMBED_MODEL` | `nomic-embed-text` | embedding model |
| `RAG_EMBED_DIM` | `768` | expected vector dim (sanity-check; warn on mismatch) |
| `RAG_REPO_ROOT` | `.` (rootProject dir at runtime) | repo to index |
| `RAG_CHUNK_SIZE` | `1000` | fixed-size chunk length (**chars** — see decision 4) |
| `RAG_CHUNK_OVERLAP` | `150` | fixed-size overlap (chars) |
| `RAG_INDEX_DIR` | `rag-index` | output dir (**gitignored**) |

**Source set to index** (default, overridable): `README.md`, `PLAN.md`, `mcp/PLAN.md`, and all
`**/*.kt` under `app/` and `mcp/`, **excluding** `build/`, `.gradle/`, `out/` and generated dirs.
(20 days of code + docs — well past the 20–30 page minimum; the task allows code as an equivalent
source.)

## Architecture / module layout — package `org.example.rag` (mirrors `org.example.mcp`)
```
rag/
  build.gradle.kts                 # mirrors mcp/build.gradle.kts (see below)
  PLAN.md                          # this file
  src/main/kotlin/org/example/rag/
    model/
      Document.kt                  # data class Document(path, fileName, type: Md|Kt, content)
      Chunk.kt                     # data class Chunk(id, text, metadata) — @Serializable
      ChunkMetadata.kt             # @Serializable: source(path), file(name), section, chunkId, strategy, ordinal
    chunk/
      ChunkingStrategy.kt          # interface { val name; fun chunk(doc: Document): List<Chunk> }
      FixedSizeChunking.kt         # size + overlap sliding window over chars
      StructuralChunking.kt        # md: split on headers; kt: split on top-level fun/class/object
    embed/
      Embedder.kt                  # interface { suspend fun embed(text: String): FloatArray }
      OllamaEmbedder.kt            # Ktor CIO client → POST {OLLAMA_HOST}/api/embeddings
    index/
      VectorIndex.kt               # interface { fun add(chunk, vector); fun search(q, topK): List<SearchResult>; save/load }
      JsonVectorIndex.kt           # in-memory List<IndexEntry> + cosine; persists JSON; stores NORMALIZED vectors
      SearchResult.kt              # data class(chunk, score)
      VectorMath.kt                # normalize(FloatArray): FloatArray; cosine(a,b): Float (dot of unit vectors)
    load/
      DocumentLoader.kt            # walks RAG_REPO_ROOT, applies include/exclude, builds List<Document>
    pipeline/
      IndexingPipeline.kt          # load → per strategy: chunk → embed → normalize → add → save; returns comparison
      StrategyStats.kt             # data class: strategy, chunkCount, min/max/avg chunkSize, totalChars
    config/RagConfig.kt
    Main.kt                        # entry point: runBlocking { pipeline for BOTH strategies; print comparison }
  src/test/kotlin/org/example/rag/
    FakeEmbedder.kt                # deterministic vector from text (hash-seeded) — no live Ollama
    FixedSizeChunkingTest.kt
    StructuralChunkingTest.kt
    VectorMathTest.kt
    JsonVectorIndexTest.kt         # round-trip save/load + search ranking via FakeEmbedder
```

### Indexing pipeline (data flow)
`DocumentLoader.load()` → `List<Document>` → **for each `ChunkingStrategy`**: `chunk(doc)` per doc →
`List<Chunk>` (each carries metadata) → `OllamaEmbedder.embed(chunk.text)` → `VectorMath.normalize(v)`
→ `JsonVectorIndex.add(chunk, unitVector)` → `index.save(RAG_INDEX_DIR/index-<strategy>.json)`. Then
build a `StrategyStats` per strategy and print the comparison table. **Both** indexes are written
(the comparison is a task requirement; Day 22 picks one to query).

### JSON index shape (`JsonVectorIndex`, kotlinx.serialization)
```jsonc
{
  "model": "nomic-embed-text",
  "dimension": 768,
  "strategy": "fixed-size",          // or "structural"
  "normalized": true,
  "entries": [
    { "vector": [/* 768 unit-normalized floats */],
      "chunk": { "id": "...", "text": "...",
                 "metadata": { "source": "app/.../Foo.kt", "file": "Foo.kt",
                               "section": "fun buildSystemPrompt", "chunkId": "...",
                               "strategy": "fixed-size", "ordinal": 3 } } }
  ]
}
```

### `rag/build.gradle.kts` (mirror `:mcp`, minus the MCP SDK / server bits)
- plugins: `alias(libs.plugins.kotlin.jvm)`, `alias(libs.plugins.kotlin.serialization)`, `application`.
- deps: `ktor.client.core`, `ktor.client.cio`, `ktor.client.content.negotiation`,
  `ktor.serialization.kotlinx.json`, `kotlinx.serialization.json`, `kotlinx.coroutines.core`;
  `runtimeOnly(libs.slf4j2.nop)`; tests: `kotlin-test-junit5`, `libs.junit.jupiter.engine`,
  `junit-platform-launcher`. **No `ktor-client-mock`** — tests use `FakeEmbedder`, not a mock HTTP engine.
- `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }`;
  `application { mainClass = "org.example.rag.MainKt" }`;
  `tasks.named<Test>("test") { useJUnitPlatform() }`.
- `tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }` — so `RAG_REPO_ROOT="."`
  and `RAG_INDEX_DIR="rag-index"` resolve at the repo root (matching the root-anchored gitignore
  rule), exactly like `:app`'s `run` / `runDigest`.
- `settings.gradle.kts`: add `include("rag")`.
- `.gitignore`: add root-anchored `/rag-index/` (runtime artifact; never committed), same style as
  `/mcp/out/`.

## Locked decisions
1. **Three abstractions are the seams.** `Embedder` (→ cloud embedder later), `VectorIndex`
   (→ SQLite later; `search(..., topK)` → Day-22 retrieval + reranking), `ChunkingStrategy`
   (swap/compare). Implement the minimum: `OllamaEmbedder`, `JsonVectorIndex`, `FixedSizeChunking`
   + `StructuralChunking`.
2. **Store NORMALIZED vectors** (unit length) in the index. Cosine then reduces to a dot product,
   and Day-22 query vectors normalize the same way. `normalized: true` recorded in the index header.
3. **In-memory cosine, JSON on disk.** Small volume (a few hundred chunks) — no SQLite/FAISS. The
   `VectorIndex` abstraction leaves the SQLite door open.
4. **Chunk by characters, not tokens** (no tokenizer dependency). Defaults ~1000 chars / 150 overlap
   (≈ the course's 500–1000-token order of magnitude). Documented as an approximation; swapping in a
   token counter later stays internal to `FixedSizeChunking`. Overlap avoids losing context at
   chunk boundaries.
5. **Two strategies, compared (task requirement).**
   - *Fixed-size:* sliding window of `size` chars with `overlap`; `section` metadata = `null`/`"—"`.
   - *Structural:* `.md` split on markdown headers (`^#{1,6} `) into header-titled sections; `.kt`
     split on top-level `fun`/`class`/`object` boundaries (regex/brace-simple — no full parser,
     good enough for logical blocks); `section` = header text or `fun/class` name.
   - Comparison prints per strategy: chunk count, min/max/avg chunk size, total chars, + a one-line
     qualitative note (structural = fewer, semantically aligned, uneven; fixed = uniform, may split
     mid-idea).
6. **Metadata per chunk (task requirement):** `source` (file path), `file` (file name), `section`
   (header / function name if applicable), `chunkId` — plus `strategy` and `ordinal` for provenance.
   Stored with each chunk.
7. **Ollama call:** `POST {OLLAMA_HOST}/api/embeddings`, body `{"model": <model>, "prompt": <text>}`,
   response `{"embedding": [768 floats]}` (single-text endpoint). Ktor CIO client + content
   negotiation, reusing `:app`'s `AnthropicClient` pattern. **Verify the exact request/response
   field names against a live Ollama (`curl`) or Context7 at build time** before finalizing the DTOs;
   warn if the returned dim ≠ `RAG_EMBED_DIM`. No live Ollama is hit in tests.
8. **`:rag` is autonomous now, `:app`-dependable later.** Its own `Main.kt` entry point (like `:mcp`'s
   server / client-demo split). No `:app`/`:mcp` coupling this day; the later "integrate RAG into
   agent" day adds `:app → :rag`, never the reverse.
9. **Branch:** `feat/day21-rag-indexing`, cut from fresh `main`.

## Implementation steps (later build session)
Each step ends at a build gate (`./gradlew :rag:build`); tests land in the same commit as the code
they cover.
1. **Module skeleton** — `settings.gradle.kts += include("rag")`, `rag/build.gradle.kts`, `.gitignore
   += /rag-index/`, empty `Main.kt`. Gate: `:rag:build`.
2. **Model + chunking** — `Document`, `Chunk`, `ChunkMetadata`; `ChunkingStrategy` + both impls +
   their tests. Gate: `:rag:test`.
3. **Vector math + index** — `VectorMath`, `VectorIndex`, `JsonVectorIndex`, `SearchResult` + tests
   (round-trip, ranked search via `FakeEmbedder`). Gate: `:rag:test`.
4. **Embedder** — `Embedder`, `OllamaEmbedder` (verify Ollama API shape first). Gate: `:rag:build`.
5. **Loader + pipeline + entry point** — `DocumentLoader`, `IndexingPipeline`, `StrategyStats`,
   `RagConfig`, wire `Main.kt`. Gate: `:rag:build`, then manual E2E.

## Verification plan
**Unit tests (`./gradlew :rag:test`, no live Ollama):**
- `FixedSizeChunkingTest` — deterministic boundaries + overlap on sample text; metadata
  (`source`/`file`/`chunkId`/`ordinal`) correct; last chunk handles the remainder.
- `StructuralChunkingTest` — sample markdown splits on headers with `section` = header text; sample
  `.kt` splits on `fun`/`class` with `section` = symbol name.
- `VectorMathTest` — `normalize` yields unit length; `cosine` = 1 for identical, ~0 for orthogonal,
  symmetric.
- `JsonVectorIndexTest` — add chunks with `FakeEmbedder` vectors → save → load → entries / metadata /
  vectors round-trip; `search(queryVector, topK)` returns correctly **ranked** results (highest
  cosine first).

**Manual E2E (user runs; Ollama up at `http://localhost:11434`):**
`./gradlew :rag:runIndexer` → loads repo docs (`README.md`, `PLAN.md`, `mcp/PLAN.md`, `**/*.kt`), chunks
with both strategies, embeds via Ollama, normalizes, writes `rag-index/index-fixed-size.json` +
`rag-index/index-structural.json`. Confirm: (a) each JSON has `entries` with 768-float vectors + full
metadata; (b) the 2-strategy comparison table prints (chunk counts / sizes / differences). `:app`,
`:mcp`, digest untouched; `rag-index/` is gitignored.

---

# Day 22 — RAG query (retrieval pipeline + agent RAG modes)

Day 22 closes the loop: question → retrieve top-K chunks → grounded LLM answer with sources. `:rag`
gains a **retriever** (still no generative LLM — that lives in `:app`); `:app` gains `:app → :rag` and
a dedicated RAG Q&A path. Branch: `feat/day22-rag-query`, cut from fresh `main` after PR #13 merged.

## Retrieval pipeline (`:rag`, package `org.example.rag.retrieve`)
A staged, composable pipeline so Day 23 (rerank + query rewrite) plugs into passthrough seats:
```
[QueryTransformer] → embed (OllamaEmbedder) → VectorIndex.search(topK) → [Reranker] → List<SearchResult>
```
- `RagRetriever` — the retrieval-only seam (`suspend retrieve(question, topK): List<SearchResult>`).
- `DefaultRagRetriever(embedder, index, queryTransformer = NoOp, reranker = NoOp)` — the Day-22 impl.
- `QueryTransformer` / `Reranker` — interfaces with **no-op identity** default impls
  (`NoOpQueryTransformer`, `NoOpReranker`). Wired but passthrough — the explicit **Day-23 seats**.
- **Reuses `SearchResult(chunk, score)`** as the hit type (no new `RetrievedChunk`). Sources come from
  `chunk.metadata` (`file`, `section`).
- `IndexStrategy` enum (`STRUCTURAL` / `FIXED`) + `parse`; `RagConfig` gains `topK` (default 5,
  `RAG_TOP_K`), `indexStrategy` (default structural, `RAG_INDEX_STRATEGY`), and `indexFile(strategy)`
  = `<indexDir>/index-<name>.json`. The chosen index is loaded via `JsonVectorIndex.load`.

## Agent RAG modes (`:app`, package `org.example.ragmode`)
- `RagResponder(llmClient, config, retrieverFactory)` — the GENERATOR half, **decoupled** from the
  task-state machine / memory / invariants (clean with/without-RAG comparison):
  - `answer(q, useRag = false)` — bare question → LLM (baseline); retriever untouched.
  - `answer(q, useRag = true)` — retrieve top-K → context block (each chunk prefixed
    `[Source: <file>, section: <section>]`) + anti-hallucination system prompt (answer only from
    context, cite sources, admit gaps) → LLM → **deterministic `Sources: [file:section, …]` append**
    from metadata (reliable, independent of the model citing).
  - Retrievers built/cached per `IndexStrategy`; `setStrategy` switches the index.
- REPL: `:rag [on|off]` toggles RAG mode (a plain question then routes to `RagResponder`, a stateless
  path not written to short-term memory); `:index [structural|fixed]` switches the queried index. Both
  mirror the `:mode` command idiom. `Repl` takes a nullable-defaulted `ragResponder` (like the optional
  agentic loop), so existing tests compile unchanged. `Main.kt` wires an `OllamaEmbedder` + responder
  and closes the embedder in `finally`.

## Comparison runner + eval set
- `CompareMain` (`org.example.ragmode.CompareMainKt`, task `:app:runRagEval`, mirrors `runDigest`):
  runs each control question through BOTH modes side by side + prints expectation + expected/retrieved
  sources.
- `ControlQuestion` + `app/src/main/resources/rag-eval/control-questions.json` — **10 English**
  questions about THIS codebase (English on purpose: the corpus is English; cross-language retrieval
  is weak). Each has `expectation` + `expectedSources`.

## Verification (Day 22)
- **Unit (`:rag:test`, `:app:test`, no Ollama):** retriever ranks a tiny in-memory index built with
  `FakeEmbedder` and respects `topK`; passthrough stages are identity; `RagResponder` builds the
  `[Source: …]` context + anti-hallucination instruction + `Sources:` append; baseline path never
  calls the retriever; `IndexStrategy.parse` + `RagConfig` defaults; REPL `:rag`/`:index` state; eval
  set loads 10 well-formed questions.
- **Manual E2E (Ollama up, index built via `./gradlew :rag:runIndexer`):** `./gradlew run` (launches
  ONLY the `:app` REPL — `:app` is the sole owner of the `run` task) → `:rag on` → English question →
  grounded answer + real `Sources:`; `:rag off` → normal agent; `:index fixed`/`structural` switches;
  `./gradlew :app:runRagEval` → 10 questions, both answers + expectations side by side. Days 11–21
  untouched.

## Run tasks (task-name ownership)
`application` is applied to `:app`, `:mcp`, and `:rag`, so a bare `./gradlew run` would otherwise fan
out to every module's `run`. Only `:app` keeps `run` (the interactive REPL); the other entry points are
explicit tasks so `./gradlew run` is unambiguous:
- `./gradlew run` → the `:app` agent REPL.
- `./gradlew :rag:runIndexer` → build the JSON indexes (this module's indexer).
- `./gradlew :mcp:runServer` → the GitHub MCP HTTP server; `./gradlew :app:runDigest` → the digest daemon.

## OUT of scope (Day 24+, designed-for not built)
- No changes to Day-21 indexing; no new embedder or model; nothing model-specific.
- No cross-encoder / Python reranker service; no external rerank API; no MMR (Day 23 uses a
  similarity-threshold heuristic — see below).

---

# Day 23 — Reranking / relevance filter + query rewrite (IMPLEMENTED)

Day 23 adds the **second stage after retrieval**, filling the two Day-22 passthrough seats:

- **Reranker seat → `ThresholdReranker` (pure Kotlin, `:rag`).** A similarity-threshold relevance
  filter: `results.filter { it.score >= minScore }.take(keepTopK)`. Ollama exposes only the embedding
  layer (no `/api/rerank`, no cross-encoder head), so — per the task's allowed "similarity threshold /
  heuristic" — we reuse the cosine `score` already in `SearchResult`. A true cross-encoder could drop
  into the same seat later without touching the retriever.
- **QueryTransformer seat → `LlmQueryRewriter` (`:app`).** An LLM rewrite/expansion of the question
  before embedding (adds class names, synonyms, technical terms). To keep `:rag` free of a generative
  LLM, `QueryTransformer.transform` was made `suspend` (the interface stays a pure seam in `:rag`);
  the LLM-backed impl lives in `:app` and is injected into the retriever via `RagResponder.fromConfig`.
  A blank rewrite or any LLM error falls back to the original query — a failed rewrite never breaks
  retrieval.

**Wide-net → filter.** Improved retrieval passes the wide `retrieveK` (20) to `search`, then the
threshold filter drops the low-relevance tail and caps at `afterK` (5). Baseline stays the Day-22
`search(topK=5)` with NoOp stages. `afterK == topK == 5`, so both feed ≤5 chunks to the LLM — the only
differences are rewrite + wide net + threshold.

**Before/after counts.** `RagRetriever.retrieve` now returns `RetrievalResult(results, retrievedCount)`
(`keptCount = results.size`), so the pre-filter vs post-filter counts are visible; they thread into
`RagAnswer` (`retrievedBefore` / `keptAfter`) and the eval.

**Config (`RagConfig`):** `scoreThreshold` (`RAG_SCORE_THRESHOLD`, 0.5 — a documented approximation,
tune against the eval scores), `retrieveK` (`RAG_RETRIEVE_K`, 20), `afterK` (`RAG_AFTER_K`, 5); `topK`
(5) still drives the baseline path.

**Comparison + interactive:** `:app:runRagEval` (`CompareMain`) now runs **baseline vs improved** over
the 10 control questions, printing both answers, before→after counts, retrieved sources with cosine
scores, and the expectation/expected sources. The REPL adds `:filter [on|off]` to toggle the improved
pipeline live (a plain `:rag on` question then uses the current toggle).

**Tests (`:rag:test`, `:app:test`, no Ollama/API key):** `ThresholdRerankerTest` (drop/keep + afterK
cap + empty when all below cutoff); `DefaultRagRetrieverTest` (threshold shrinks + before/after counts);
`LlmQueryRewriterTest` (rewrite via a fake LLM + blank/error fallback); `RagResponderTest` (improved
uses `retrieveK` + reports counts/scores; baseline unregressed); `ReplRagTest` (`:filter` toggles).

**Manual E2E (Ollama up, index built, `ANTHROPIC_API_KEY`):** `./gradlew :app:runRagEval` shows the
filter's effect (retrieved 20 → kept ≤5) and improved retrieval surfacing relevant chunks a bare query
missed (e.g. Q9 → `JsonVectorIndex.kt`); `./gradlew run` → `:rag on` + `:filter on/off` compares live.
