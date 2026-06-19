# Implementation Plan — CLI Agent, Days 11–15 (one unified system)

This is the single source of truth for what we are building. One system, built layer by
layer. Everything on files. Each day is an additive layer, not a separate program. The
architecture is designed up front for all 5 days **and** for a future agent swarm (the
swarm is DESIGNED FOR but NOT implemented yet).

Read this file at the start of every session before planning or writing code.

---

## Central idea: layered system-prompt assembly

The final system prompt is assembled in ONE place (`Agent.buildSystemPrompt()`):

```
buildSystemPrompt() =
    1. INVARIANTS            (Day 14, highest priority — must never be violated)
  + 2. PROFILE + LONG-TERM   (Days 12 + 11)
  + 3. WORKING memory        (Day 11 — active task context)
  + 4. STAGE PROMPT          (Day 13 — current stage's behavior)
SHORT-TERM memory (history) → goes into `messages` (Day 11)
```

Every day adds one layer here. Never scatter system-prompt construction.

---

## File storage (everything on files; `memory/` at project root, gitignored)

```
memory/
  long-term/
    profile.md         — Day 12: user profile (style/format/language/stack). MANUAL editing.
    knowledge.md       — Day 11: global decisions/habits. MANUAL (+ `:remember` command).
  working/
    tasks/<name>.md    — Days 11+13: task (strict structure + stage field). AUTO-extracted.
    active             — pointer to the active task (plain text: task name).
  invariants.md        — Day 14: hard rules, separate from the dialog.
```

### Strict task-file structure
```markdown
# Task: <name>
stage: planning            # Day 13/15: a VALID state from the enum

## Goal
<one line>

## Requirements
- ...

## Decisions
- ...

## Done
- ...

## TODO
- ...
```

---

## Code packages

```
agent/      Agent (buildSystemPrompt = single assembly point), Message, models, LlmClient  [exists]
memory/     MemoryStore (facade), ShortTermMemory (in-memory),
            WorkingMemory (tasks + active pointer), LongTermMemory (profile + knowledge),
            ResponseGenerator (interface), CombinedResponseGenerator (variant A)
profile/    Profile (Day 12)
task/       TaskState (stage enum), StagePrompts (per-stage system prompt),
            TaskStateMachine (transitions + rules), Orchestrator (drives the task),
            StageController (interface — hook for the swarm), SingleAgentController (variant A)
invariants/ InvariantStore, InvariantChecker (Day 14)
repl/       Repl (commands)  [exists, extended]
Main.kt     [exists]
llm/        AnthropicClient (Ktor)  [exists]
```

---

## Locked decisions (mechanics)

- **3 memory layers:** short-term (in-memory) / working (task files) / long-term (files).
- **Auto-extraction → WORKING memory only.** Long-term is MANUAL.
- **Combined call:** one structured-output request returns `{reply, task_update}`.
  Behind a `ResponseGenerator` interface so it can be swapped to a separate-call
  implementation (`SeparateResponseGenerator`) without touching Agent/REPL.
- **Structured-output parsing:** max_tokens high enough that the JSON (reply + full task
  markdown) is never truncated; on parse failure → FALLBACK: show the raw reply, do NOT
  touch the task, never crash.
- **State machine:** designed for a swarm (variant B), implemented as a single agent
  (variant A). `StageController` interface: `SingleAgentController` now,
  `MultiAgentController` later.
- **Transitions:** flag `TransitionMode`, DEFAULT = AUTO with two-level validation
  (see below). CONFIRM is an option.

---

## Transitions (Days 13/15) — DEFAULT = AUTO, two-level validation

- **Level 1 (code, cheap, no tokens):** `canTransition(from, to)` against
  `allowedTransitions` (is the edge legal?) + artifact-readiness check (is there a plan /
  is the stack specified?).
- **Level 2 (LLM, only if code passed):** "is the stage really complete and the artifact
  well-developed?" — catches "a plan exists but it's bad".
- **CONFIRM (optional flag):** same as AUTO plus explicit user confirmation to transition.

Principle: code rejects clearly-invalid transitions for free; the LLM catches weak artifacts.

---

## Step-by-step plan (each step = one Claude Code prompt + a build gate between steps)

### Step 1 — Day 11: Memory model  [CURRENT]
- `memory/` package: ShortTerm / Working / LongTerm / MemoryStore.
- File storage (layout above), strict task structure, `active` pointer.
- `ResponseGenerator` + `CombinedResponseGenerator` (structured `{reply, task_update}`, fallback).
- Auto-extract into the working task; long-term is manual.
- `buildSystemPrompt` composes: long-term + active task + base. History → messages.
- REPL: `:task-new`, `:task-switch`, `:task-list`, `:task-show`, `:remember`.

### Step 2 — Day 12: Personalization
- `Profile` on top of long-term (`profile.md`): style, format, language, stack, constraints.
- Injected into every request (into the prompt assembly).
- Verify: different profiles → different answers. (Nearly free after Day 11.)
- Let the user set/change the profile (command or manual file edit).

### Step 3 — Day 13: Task state machine  [HEAVIEST]
- `TaskState` enum: PLANNING, EXECUTION, VALIDATION, DONE.
- `StagePrompts`: a distinct system prompt per stage (agent behaves differently per stage).
- `Orchestrator`: drives the task; the agent signals "stage complete" in the structured reply.
- `StageController` interface (swarm hook) + `SingleAgentController` (variant A — one agent,
  different prompts).
- Auto-transitions: DEFAULT AUTO (two-level validation above), CONFIRM flag.
- Stage stored in the task file (`stage`), survives restart (pause/resume).

### Step 4 — Day 14: Invariants
- `invariants.md` — hard rules, separate from the dialog.
- Injected into the system prompt FIRST (highest priority).
- `InvariantChecker`: the agent explicitly considers invariants, REFUSES to violate, explains.
- On conflict between a request and an invariant: TURN THE USER AWAY immediately (refuse +
  explain), NO replanning loops (simpler/cheaper, and exactly the Day 14 requirement).
- Demo invariants (meaningful ones): e.g. Kotlin-only stack, no third-party auth SDKs, a
  specific architecture — so the refusal is visible on conflict.

### Step 5 — Day 15: Controlled transitions (extends Step 3)
- `allowedTransitions`: table of legal edges.
  - FORWARD: planning→execution→validation→done.
  - BACKWARD for rework: validation→execution (edges both ways where it makes sense).
- `canTransition(from, to)`: blocks skipping (planning→done is forbidden).
- Transition condition is REAL artifact readiness, not just "agreed" (the agent is demanding,
  doesn't accept hand-waving — "the word 'agreed' is not enough").
- Verify (test): commands like "skip, go to execution" → the agent blocks and explains why.

---

## Agent swarm (recommended by the course author) — DESIGNED FOR, NOT IMPLEMENTED

- Course author: "ideally hook up a swarm", "try it on ONE stage with 2–5 agents exchanging
  views through an orchestrator", "not on every stage".
- NOT a Day-15 requirement — an optional deepening. Most participants ship without a swarm.
- **Hook:** the `StageController` interface. Now `SingleAgentController` (one agent). Later
  `MultiAgentController` for one stage (e.g. validation/research): several agents argue/augment
  via an orchestrator. Plugs in WITHOUT changing `Orchestrator`.
- Implement ONLY if there is time left after all 5 days.

---

## Invariants — refinements (designed for, mostly not implemented yet)

BASIC (implement in Day 14):
- Global invariants in `invariants.md` → injected first → refuse + explain on violation.
- On request/invariant conflict: turn the user away immediately, NO replanning loops.

DESIGNED FOR, NOT IMPLEMENTED (optional, if time):
- Per-stage / per-agent invariants (author: "almost every agent has a pack of invariants").
  Structure allows global + optional per-stage; for now GLOBAL only.
- Return to the SOURCE of a problem (if the root is in planning, go back to planning, not
  execution — otherwise execution was wasted). Allow different backward edges
  (validation→planning AND validation→execution); the "find the source" logic comes later.
- Early validation to save tokens (fail on an early stage → don't spend on execution).

NOT NOW (this is the swarm, deferred):
- Agents propose options to an orchestrator that decides given the user's request.
- Inter-stage rework loops with automatic source detection.

---

## Guiding principle
Design for the maximum (5 days + swarm), implement the minimum that genuinely satisfies the
tasks. The hooks (`ResponseGenerator`, `StageController`, `allowedTransitions`) allow
deepening without rewrites. Build incrementally, with a build gate between steps.
