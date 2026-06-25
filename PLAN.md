# Implementation Plan — CLI Agent, Days 11–15 (one unified system)

This is the single source of truth for what we are building. One system, built layer by
layer. Everything on files. Each day is an additive layer, not a separate program. The
architecture is designed up front for all 5 days **and** for a future agent swarm (the
swarm is DESIGNED FOR but NOT implemented yet).

Read this file at the start of every session before planning or writing code.

> **Day 16 (Connect to MCP):** a separate, autonomous module — see [`mcp/PLAN.md`](mcp/PLAN.md).

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

Day 15 adds to layer 4: the stage prompt also lists the allowed transitions FROM the current
stage, read from the `TaskStateMachine` table (single source of truth), so the model proposes
only legal directions. See Step 5 (Day 15).

Every day adds one layer here. Never scatter system-prompt construction.

The INVARIANTS layer (1) is the FIRST, highest-priority section — emitted before the
profile/long-term section and before the stage prompt, so it frames everything below it.
It carries a CODE-owned instruction (fixed in code, NOT user text): the invariants are
non-negotiable; before proposing any solution/decision/design the agent must check it
against every invariant; if a request or its own proposed solution would violate one, it
must NOT propose the violating solution — instead refuse it, name which invariant is
violated and why, and propose an alternative that satisfies ALL invariants (never work
around an invariant via a different violation). If there are no invariants, NOTHING is
injected (no empty header). Because invariants live in the system prompt, they apply across
ALL stages (planning / execution / validation) — this also lets validation catch invariant
violations. See Step 4 (Day 14) for the full design.

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
  invariants.md        — Day 14: GLOBAL hard rules (flat list), separate from the dialog.
```

### Invariants file structure
`memory/invariants.md` holds a FLAT LIST of invariant lines, mirroring how `knowledge.md`
is stored (plain markdown bullets, one invariant per line):
```markdown
- No SharedPreferences or EncryptedSharedPreferences
- Kotlin-only stack; no third-party auth SDKs
- Clean Architecture: domain depends on nothing framework-specific
```
- **Global**, not per-task: one file applies to ALL tasks (no per-task invariant files).
- **String-typed:** `InvariantStore` returns `List<String>` of lines — `memory/` stays free
  of any `task.*` imports (same one-directional dependency discipline as the rest of `memory/`).
- **Missing/empty file = no invariants** (nothing is injected; never crashes).
- Stored separately from the dialog (file, not history) — satisfies the Day 14
  "stored separately" requirement. Manual editing + REPL commands (see Step 4).

### Strict task-file structure
```markdown
# Task: <name>
stage: planning            # Day 13: a VALID state from the enum (CODE-owned)
stage_complete: false      # Day 13/3c: CODE-owned, persisted (survives restart)
proposed_transition:       # Day 15: CODE-owned pending direction (set after re-validation;
                           #   absent until one is pending, so `:next` can accept a backward
                           #   target after a restart). Cleared on entering a stage.
step:                      # Day 13: current step within the stage
expected_action:           # Day 13: what the system expects next

## Goal
<one line>

## Requirements
- ...

## Decisions
- ...

## Implementation
- ...

## Validation
- ...

## Done
- ...

## TODO
- ...
```

---

## Code packages

```
agent/      Agent (buildSystemPrompt = single assembly point), Message, models, LlmClient,
            ResponseGenerator (interface), CombinedResponseGenerator (variant A)  [exists]
memory/     MemoryStore (facade), ShortTermMemory (in-memory),
            WorkingMemory (tasks + active pointer), LongTermMemory (profile + knowledge)
profile/    Profile (Day 12)
task/       TaskState (stage enum), StagePrompts (per-stage system prompt),
            TaskStateMachine (transitions + rules; Day 15: + backward rework edges + code
            re-validation of a model-proposed direction), Orchestrator (drives the task),
            StageController (interface — hook for the swarm), SingleAgentController (variant A)
invariants/ InvariantStore (flat string list; Day 14). Enforcement is the injected
            code-owned instruction — no separate runtime checker gates turns.
repl/       Repl (commands)  [exists, extended]
Main.kt     [exists]
llm/        AnthropicClient (Ktor)  [exists]
```

The response generator lives in `agent/`, not `memory/`: it depends only on `LlmClient`
and works with strings (`currentTask` in, `taskUpdate` out) — the Agent connects it to
memory. This keeps dependencies one-directional (`agent → memory`, `agent → llm`;
`memory` depends on nothing).

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
- **Transitions:** flag `TransitionMode`, DEFAULT = CONFIRM (3c) with two-level validation
  (see below). AUTO (the autonomous chain) is opt-in.

---

## Transitions (Days 13/15) — DEFAULT = CONFIRM, two-level validation

- **Level 1 (code, cheap, no tokens):** `canTransition(from, to)` against
  `allowedTransitions` (is the edge legal?) + artifact-readiness check (is there a plan /
  is the stack specified?).
- **Level 2 (LLM, only if code passed):** "is the stage really complete and the artifact
  well-developed?" — catches "a plan exists but it's bad".
- **CONFIRM (default, 3c):** same validation as AUTO, but a ready transition is NOT
  auto-performed — the agent pauses at the boundary and waits for the user's `:next`. AUTO is
  the opt-in autonomous chain.

Principle: code rejects clearly-invalid transitions for free; the LLM catches weak artifacts.

- **Day 15 extends this:** `allowedTransitions` gains BACKWARD rework edges and the model gains
  an OPTIONAL `proposed_transition` (a *direction*). The model proposes per the table (forward
  only on SUCCESS, backward on problems); then code re-validates that proposal by the SAME table
  (legal edge) + readiness — code is the final arbiter. "No final without validation" follows
  structurally (validation→done is proposed only when validation passed). See Step 5.

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
**Storage:**
- Multiple profiles in `memory/long-term/profiles/<name>.md` (free-form markdown).
- An active-profile pointer file in `memory/long-term/` holds the active profile name
  (persists across restart, like the working-memory `active` pointer).
- The previously-stubbed `memory/long-term/profile.md` role is taken over by the active
  profile from `profiles/` (a default profile is fine if none is active).

**Profile content** (free-form markdown), fields stored as lines `- <field>: <value>`:
```
# Profile: <name>
- style: concise
- format: prose
- language: English
- stack: Kotlin, Android
- constraints: prefer platform APIs, Clean Architecture
```

**REPL commands:**
- `:profile-new <name>` — create a new EMPTY profile (just a `# Profile: <name>` header,
  no preset fields), set it active. Empty-on-creation so filling it via commands is visible on demo.
- `:profile-switch <name>` — set the active profile (writes the active-profile pointer).
- `:profile-show` — print the active profile content.
- `:profile-set <field> <value>` — set a preference FIELD, OVERWRITING if it exists.
  Stored as a markdown line `- <field>: <value>`; finds the line starting with `- <field>:`
  and replaces its value, or appends a new line if absent. One line per field (no duplicates).
  E.g. `:profile-set style concise` then `:profile-set style detailed` → a single
  `- style: detailed` line.
- `:profile-list` — list available profiles (mark active).
- Manual editing of `profiles/<name>.md` also works.

**Assembly:** `buildSystemPrompt()` injects the ACTIVE profile content (via the active-profile
pointer) into the long-term section, attached to every request automatically. The Day 11
`knowledge.md` injection stays. Short-term/working/structured-call mechanics unchanged from Day 11.

**Verify** (Day 12 requirement): different profiles → different answers. Demo: a "concise,
English" profile vs a "detailed, Russian" profile on the SAME task (secure-storage); answers
differ in style/format/language, showing the profile is applied automatically.

### Step 3 — Day 13: Task state machine  [HEAVIEST]
**Task state (fields in the task file):**
- `stage` — PLANNING / EXECUTION / VALIDATION / DONE.
- `stage_complete` — `true|false` (3c). CODE-owned and PERSISTED in the header so completion
  survives a restart (CONFIRM pause/resume). The model never sets it via `task_update` — it is
  re-asserted after every overwrite, exactly like `stage`; missing in old files → `false`.
- `step` — the current step within the stage.
- `expected_action` — what the system expects next.

**Task structure (sections):** `## Goal` / `## Requirements` / `## Decisions` /
`## Implementation` / `## Validation` / `## Done` / `## TODO`. Each stage produces its
artifact section: planning → Requirements + Decisions, execution → Implementation,
validation → Validation.

**`StagePrompts`:** a distinct system prompt per stage, each containing (1) the stage
behavior and (2) a demanding, checkable completion criterion the model uses to judge
`stage_complete`. Criteria are content-specific and strict (don't accept hand-waving —
e.g. planning is complete only when requirements are concrete, key decisions are made,
and no blocking open questions remain).

**Auto-transition mechanics:**
- The combined structured call returns `stage_complete: boolean` (only whether the
  CURRENT stage is complete — it does NOT propose the next stage).
- The NEXT stage is determined by CODE from the transition table
  (planning→execution→validation→done). The model never chooses the next stage — this
  prevents skipping and invalid stages. (Day 15 evolves this: the model may ADDITIONALLY
  propose a *direction* via an optional `proposed_transition` — incl. backward rework — but
  CODE remains the final arbiter, re-validating the proposal against this SAME table + artifact
  readiness. The model proposes; code decides. See Step 5.)
- Two-level validation: **Level 1 (code, cheap, no tokens)** — is the edge legal
  (`allowedTransitions`) AND is the current stage's artifact section non-empty?
  **Level 2 (model)** — `stage_complete` judged against the stage criterion. A
  transition happens only if the model says complete AND code confirms (legal +
  artifact present).

**Autonomous stage chain — AUTO mode (3b):** In AUTO mode (opt-in via `:mode auto`), after a
user turn the agent advances through stages AUTONOMOUSLY within that single turn — like a real
agent (e.g. Claude Code) that doesn't stop to ask "should I continue?" between steps. (Under the
default CONFIRM mode the same per-stage work runs but a ready transition is deferred to `:next`
— see Transition modes below.) The loop per user turn:
- Call the agent on the current stage; apply its `task_update`.
- If `stage_complete == true`:
  - **Artifact ready** (Level 1: required sections non-empty) AND edge legal → transition
    (CODE picks the next stage from the table; the model never picks it). Print
    `>>> Stage transition: <from> → <to>`. If the new stage is DONE → **STOP** (task finished).
    Otherwise **CONTINUE** the loop on the new stage (the agent immediately starts working the
    next stage — no "shall we proceed?" turn).
  - **Artifact NOT ready** → exactly ONE self-correction follow-up (print
    `>>> Refining <stage> artifact before advancing...`, feed the model the empty-section
    feedback — "You marked the stage complete, but the <section> section is empty. Fill it with
    concrete content before the stage can advance." — apply its `task_update`, re-evaluate once).
    If it becomes ready → transition and continue; if still not ready → **STOP** and return to
    the user (stalled — needs input).
- If `stage_complete == false` → the agent isn't done with this stage (e.g. it asked the user a
  question / needs input) → **STOP** and return to the user.

**Stop conditions** (where the autonomous chain ends and control returns to the user):
- **DONE reached** — the task is complete.
- **User input needed** — the model did not mark the stage complete (it's asking a question or
  needs more from the user).
- **Stalled** — the model marked the stage complete but the artifact isn't ready even after the
  one self-correction (no progress → stop rather than loop).

Between these stop conditions the agent proceeds through stages on its own. There is **no fixed
per-turn transition cap** — the chain runs as far as genuine progress allows (each transition
requires a real, code-verified artifact, so it cannot advance on empty stages). The chain cannot
spin without progress: every loop iteration either advances on a ready artifact, stops for user
input, stops at DONE, or stops stalled. (Optional manual interruption / convenience limits are
NOT part of this; they can be added later if needed.)

**Step-by-step display (pre-decided):** print as the chain runs — each stage's agent reply is
printed, with `>>> Stage transition: <from> → <to>` between stages and `>>> Refining ...` before
any self-correction reply, so the user sees progress in real time (not buffered to the end).

**Transition modes (3c):**
- `TransitionMode`: **CONFIRM (default)** / AUTO, toggled by `:mode confirm` / `:mode auto`.
  Session state, NOT persisted (each start defaults to CONFIRM); the stage itself still persists.
- CONFIRM (default): process exactly ONE stage per user turn. When the stage is complete and the
  artifact is ready, DO NOT advance — print `>>> Stage '<from>' complete and ready to advance to
  '<to>'. Type :next to continue.` and wait. The agent pauses at EVERY boundary
  (planning→execution→validation→done) — this is what satisfies the Day 13 pause/resume
  requirement. Readiness is read from the PERSISTED header (`stage_complete` + artifact), so a
  stage completed last session is advanceable after a quit+restart.
- AUTO: the autonomous chain above — a ready transition is performed automatically and the agent
  continues to the next stage. Same validation as CONFIRM; only deferral differs.
- **`:next [instruction]`** — advance ONE stage AND immediately run the new stage. Readiness reuses
  the SAME `nextStage` + `canTransition` + `isArtifactReady` checks (from the persisted header).
  On success it transitions then runs the new stage through the normal agent path with `instruction`
  as input (or a neutral default when omitted) — EXCEPT advancing into DONE, which is terminal and
  runs no turn. Refuses (and runs nothing) if not ready (artifact incomplete / not marked complete)
  or already at DONE. `:mode` with no arg shows the current mode.
- **Day 15 note:** the transition TARGET is generalized from "forward next" to "the
  model-proposed, code-validated direction (forward on success / backward on rework)". `:next`
  accepts the proposed direction; both modes still validate in code before performing. See Step 5.

**Display (all from CODE, reliable — not from the model):**
- REPL prints a `[stage: <stage> · step: <step>]` label with the agent's reply.
- On a transition, REPL prints an explicit notice: `>>> Stage transition: <from> → <to>`.
  (This same notice mechanism will show BLOCKED transitions in Day 15.)

**Architecture:** `TaskState` enum; `StagePrompts`; `TaskStateMachine` (transition table +
validation; Day 15 adds backward rework edges + code re-validation of a model-proposed
direction); `Orchestrator` (drives the task); `StageController` interface (swarm hook) +
`SingleAgentController` (variant A — one agent, different prompts). Stage persists in the
task file, surviving restart (pause/resume — reuses Day 11 persistence). The swarm
(`MultiAgentController`) remains designed-for, NOT implemented.

**Verify** (Day 13 requirements): stage drives behavior (different `StagePrompt` per
stage); auto-transitions occur with validation; pause at any stage and resume without
re-explaining (restart → stage persisted → continue). Demo on the secure-storage task.

### Step 4 — Day 14: Invariants
**Purpose:** invariants are hard constraints the agent must NEVER violate — architecture,
technical decisions, stack constraints, business rules. Requirements: stored separately from
the dialog; explicitly considered in the agent's reasoning; the agent refuses solutions that
violate them.

**Storage:** a GLOBAL `memory/invariants.md` — a FLAT LIST of invariant lines, mirroring
`knowledge.md` (see "Invariants file structure" in File storage). String-typed
(`InvariantStore` → `List<String>`); `memory/` stays free of `task.*` imports.
Missing/empty file = no invariants. Global (all tasks), not per-task.

**Injection (single assembly point):** invariants are injected as the FIRST, highest-priority
section of `buildSystemPrompt()` — before the profile/long-term section and before the stage
prompt — so they frame everything. The section carries a CODE-owned instruction (fixed in
code, NOT user text):
- the invariants are non-negotiable;
- before proposing any solution/decision/design the agent must check it against EVERY invariant;
- if a request, or its own proposed solution, would violate one, it must NOT propose the
  violating solution — instead refuse it, name WHICH invariant is violated and WHY, and propose
  an alternative that satisfies ALL invariants (never work around an invariant via a different
  violation).

If there are no invariants, nothing is injected. Because they live in the system prompt,
invariants apply across ALL stages (planning / execution / validation) — this also lets
validation catch invariant violations.

**On conflict** between a request and an invariant: TURN THE USER AWAY immediately (refuse +
explain), NO replanning loops (simpler/cheaper, and exactly the Day 14 requirement). The
refusal-and-alternative behavior is driven entirely by the injected code-owned instruction —
there is no separate code-level "checker" gating turns; the agent does the checking via the
system prompt.

**REPL commands** (mirror the `:profile-*` style):
- `:invariant-add <text>` — append an invariant line.
- `:invariant-list` — list current invariants (numbered).
- `:invariant-remove <text or index>` — remove by exact text or by 1-based index.
- `:invariant-clear` (optional) — remove all invariants.
- Manual editing of `memory/invariants.md` also works.

**Scope guard (Day 14 is purely additive):** this layer does NOT change the task state
machine, transition modes (CONFIRM/AUTO), the autonomous chain, `stage_complete`, or retry /
self-correction logic. Invariants are orthogonal — added ONLY to the system prompt.

**Architecture:** `InvariantStore` (load/append/remove/clear the flat list; string-typed).
(No separate `InvariantChecker` runtime gate is needed — enforcement is the injected
instruction; if a thin code helper is kept it only formats the section, it does not block turns.)

**Demo invariants** (meaningful ones): e.g. Kotlin-only stack, no third-party auth SDKs,
"no SharedPreferences / EncryptedSharedPreferences", a specific architecture — so the refusal
is visible on conflict.

### Step 5 — Day 15: Controlled state transitions (extends Step 3)
**Goal:** make transitions explicitly controlled — legal edges (incl. backward rework), the
model PROPOSES a direction, CODE validates it, and "no final without validation" falls out
structurally. Extends Day 13's `TaskStateMachine` WITHOUT breaking pause/resume, persisted
`stage_complete`, CONFIRM/AUTO, retry/self-correction robustness, or Days 11–12/14.

**1. States & transition table (single source of truth).** States unchanged: `planning`,
`execution`, `validation`, `done`. `allowedTransitions` gains BACKWARD edges for rework,
alongside the existing forward edges:
```
FORWARD:   planning → execution → validation → done
BACKWARD:  validation → execution   (rework the implementation)
           validation → planning    (rework the plan / requirements)
           execution  → planning    (plan was wrong; replan)
```
The table is the SINGLE SOURCE OF TRUTH for what's allowed. "Can't skip a stage" is enforced
purely by the ABSENCE of an edge: `planning → done` has no edge → illegal. No skip-forward
edges exist (no planning→validation, no planning→done, no execution→done).

**2. Model proposes, code validates (two-layer control).**
- The combined structured call gains an OPTIONAL field `proposed_transition` (a target stage,
  or absent/none). `stage_complete` is UNCHANGED from Day 13 (CODE-owned, persisted, model-reported).
- The model is TOLD, inside the stage prompt, the allowed transitions FROM the current stage —
  injected from the table (single source of truth), never hand-written per stage.
- **Forward only on SUCCESS, not mere completion:** the model proposes a FORWARD transition ONLY
  if the current stage completed SUCCESSFULLY — done AND without problems/blockers. ("Completed"
  = `stage_complete`, the work is done; "completed successfully" = done AND no blockers.) For
  VALIDATION: propose →done ONLY if validation is done AND found NO blockers. If the stage is done
  but NOT successful (e.g. validation found blockers), it proposes a BACKWARD transition to the
  stage that needs rework, NOT forward. If the stage isn't finished, it proposes nothing (empty).
- **Direction carries the verdict:** because forward is proposed only on success, a forward
  proposal IMPLIES the stage passed; a backward proposal IMPLIES problems were found. There is
  NO separate "passed" flag — pass/fail is DERIVED from the proposed direction (chosen approach).
- **"No final without validation" falls out:** validation→done is proposed only when validation
  passed (no blockers); with blockers the proposal is backward, so `done` is unreachable until the
  problems are resolved.
- **Two-layer control (CRITICAL):** the model proposes per the table; then CODE re-checks that
  proposal against the SAME `TaskStateMachine` table (legal edge) PLUS artifact readiness
  (Level 1, and Level-2 readiness where applicable). CODE is the FINAL ARBITER — an illegal or
  unsafe proposal is REJECTED even if the model made it. One table, two checks: model proposes
  within it, code re-validates by it. Control stays in code, not in trusting the model.

**3. Execution by mode** (reuses Day 13's `TransitionMode`):
- **CONFIRM (default):** the model's proposed transition is shown to the user
  (`>>> Proposed transition: <from> → <to>. Type :next to accept.`); performed only on the
  user's confirmation (`:next` / accepting the proposed direction). One stage per turn —
  preserves Day 13 pause/resume.
- **AUTO:** the proposed transition is performed automatically IF the code approved it
  (legal + ready). Same autonomous chain as Day 13; only now the direction can also be backward.
- In BOTH modes the code validates BEFORE performing. `:next` reuses the existing
  `nextStage`/`canTransition`/`isArtifactReady` path, generalized from "forward next" to
  "the proposed, code-validated target".

**4. Jump attempts & reaction (two layers, demonstrable).**
- **Code-level block:** an explicit jump attempt (e.g. `:stage done` from planning, or `:next`
  toward an illegal target) is REJECTED by code with a clear explanation, reusing the Day 13
  BLOCKED-transition notice: `>>> Blocked: can't go planning → done — that skips stages.
  Allowed from planning: execution.` (Allowed list printed FROM the table.)
- **Agent-level explanation:** if the user asks in natural language to skip ("just give me the
  final solution / skip to done"), the agent REFUSES and explains that stages can't be skipped
  (driven by the allowed-transitions text in the stage prompt). Both layers demonstrate "can't
  skip a stage" — code enforces deterministically, the agent explains.

**5. Swarm (later, optional) — extension point, no infra now.** Day 15 builds NO swarm/multi-agent
infrastructure but stays compatible with adding it later: `ResponseGenerator` remains the
extension point (a future swarm = an alternative `ResponseGenerator`/orchestrator producing the
same `GeneratedResponse`, incl. `proposed_transition`); the state machine and transition logic
operate on task state + artifact readiness, NOT on HOW the artifact was produced, so nothing
bakes in "single agent". Do NOT design orchestrator infra now (see "Agent swarm" section).

**6. Scope guard.** Day 15 ONLY extends `TaskStateMachine` (backward edges), the structured
output (optional `proposed_transition`), code re-validation, and mode-aware execution. It MUST
NOT break Day 13's pause/resume, persisted `stage_complete`, CONFIRM/AUTO, retry/self-correction
robustness, or Days 11–12/14 (memory, profiles, knowledge, invariants).

**Architecture:** extend `TaskStateMachine` (add backward edges + `allowedTargets`) + `canTransition`;
add optional `proposed_transition` to the structured-output schema + `GeneratedResponse`;
`Agent.run` (which plays the Orchestrator role) consumes the proposed direction and runs it
through `canTransition` + readiness before performing. `StagePrompts` gains the per-stage
allowed-transitions text + the forward-only-on-success rule. (`StageController` swarm hook unchanged.)

**Implementation notes (as built):**
- **Forward fallback:** advancement target = `proposed_transition ?: nextStage(stage)`. When the
  model proposes nothing, CODE falls back to the forward successor (preserves Day 13 behavior and
  tests). An *illegal* proposal is rejected outright (not downgraded to the fallback).
- **`proposed_transition` is persisted** in the task header (a CODE-owned field, set only after
  re-validation) so a backward `:next` works in the default CONFIRM mode and survives a restart;
  it is kept out of the empty-task template (inserted only when a proposal is pending).
- **AUTO stops after a backward transition** (a rework boundary): forward moves continue the chain
  (bounded by DONE), a backward move is performed then the chain stops — guarantees termination.
- **CONFIRM message:** `>>> Proposed transition: <from> → <to>. Type :next to accept.`

**Verify** (Day 15 requirements): (a) `:stage done` from planning → blocked with the
table-derived explanation; (b) natural-language "skip to done" → agent refuses & explains;
(c) validation finds a blocker → model proposes a BACKWARD edge (→execution or →planning), code
performs it, `done` not reached; (d) clean validation → forward →done; (e) Day 13 pause/resume
still works (quit mid-task, restart, `:next`). Demo on the secure-storage task.

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

IMPLEMENTED IN DAY 15:
- Backward rework edges (validation→execution, validation→planning, execution→planning) — so a
  problem can return to the stage that needs rework. See Step 5.

DESIGNED FOR, NOT IMPLEMENTED (optional, if time):
- Per-stage / per-agent invariants (author: "almost every agent has a pack of invariants").
  Structure allows global + optional per-stage; for now GLOBAL only.
- AUTOMATIC "find the SOURCE of a problem" detection (the backward edges exist as of Day 15, but
  choosing which one — root in planning → go back to planning, not execution, so execution isn't
  wasted — is currently the model's proposed direction; automatic source detection comes later).
- Early validation to save tokens (fail on an early stage → don't spend on execution).

NOT NOW (this is the swarm, deferred):
- Agents propose options to an orchestrator that decides given the user's request.
- Inter-stage rework loops with automatic source detection.

---

## Guiding principle
Design for the maximum (5 days + swarm), implement the minimum that genuinely satisfies the
tasks. The hooks (`ResponseGenerator`, `StageController`, `allowedTransitions`) allow
deepening without rewrites. Build incrementally, with a build gate between steps.

---

## Days 16–17 — MCP integration (IMPLEMENTED)

Days 16–17 connect the agent to the Model Context Protocol. Full design lives in
[`mcp/PLAN.md`](mcp/PLAN.md); this is the agent-side summary.

- **Day 16:** new, decoupled `:mcp` module — an `McpClient` connects to an MCP server over stdio
  (behind a transport factory) and lists tools.
- **Day 17:** `:mcp` gains **our own GitHub MCP server** (one tool, `get_recent_commits`, over
  HTTP/SSE) and fills the `callTool` seat. `:app` gains a **Claude-Code-style agentic tool-use loop**
  (`agent/AgenticLoop`) — the NEW layer ABOVE the single round-trip: the LLM decides (real native
  tool-use, `tool_choice` auto) whether to call a tool; the loop runs it via `McpClient.callTool`,
  feeds the `tool_result` back, and repeats until a final text answer (max-iter guard).

**Where it plugs into the single assembly point:** the loop runs ONLY on the **conversational
(no-active-task) path** — `Agent` takes an optional `agenticLoop` collaborator and uses it there
instead of the one-shot reply; when it's null (MCP server down, or tests) the agent falls back to the
exact Days 11–15 behavior. The **structured task path (`{reply, task_update}` forced-tool-choice)
stays UNCHANGED** — the two `tool_choice` modes are never mixed. A future "tools during task
execution" step would reuse this same loop as a pre-step (tools first, then the structured call).

- **First coupling:** `:app → :mcp` (one-directional; `:mcp` never depends on `:app`). Anthropic
  tool-use concepts live in `:app`; GitHub/MCP concepts in `:mcp`; `agent/McpToolAdapter` is the only
  MCP `Tool` → Anthropic `ToolSpec` translation point. Tools are discovered live via `listTools()`.
- **New round-trip primitive:** `LlmClient.runToolTurn(...)` (native tool-use, content blocks
  confined to `AnthropicClient`); `CombinedResponseGenerator` is untouched.
- **Run order:** `./gradlew :mcp:run` (HTTP server), then `./gradlew :app:run`. If the server is
  unreachable the agent still runs (no tools).

**IMPLEMENTED IN DAY 18:** scheduler/periodic execution, persistence, 24/7 background — see below.
Still NOT implemented: physical VPS deploy (the digest daemon is deploy-ready in principle, but
deployment is out of scope).

---

## Day 18 — Scheduler & background tasks (periodic commit digest) — IMPLEMENTED

A NEW background **digest run mode** for `:app`: an agent that "runs 24/7 and periodically emits a
summary." On a timer it calls the existing Day-17 `get_recent_commits` tool, computes the delta of
NEW commits vs. the last-seen set, persists state, and prints an aggregated summary each tick.

**Guiding principle (locked): the agent owns scheduling/aggregation; the MCP server stays a thin
adapter.** MCP/SDK has no built-in scheduling — it lives in the app layer.

### Scope
- **IN:** a separate digest/daemon run mode in `:app`; a coroutine scheduler loop; JSON persistence
  of last-seen SHAs + counters (separate file from `memory/`); delta + stats aggregation; periodic
  summary output; reuse of `get_recent_commits` via the existing MCP client.
- **OUT:** no new MCP tool; no SQLite; no VPS deploy; no MCP Tasks/Subscriptions; **interactive mode
  (Days 11–17) is unchanged** (`Main.kt`/`Repl`/`Agent`/`AgenticLoop`/`MemoryStore` untouched).
- **ONE approved server edit** (the only exception to "server unchanged"): `get_recent_commits` now
  leads each output line with the short SHA it already fetched (`Commit.sha` carried through
  `CommitDto.toCommit()` → `format()`), giving the digest a stable commit identity to diff on. No new
  tool, endpoint, or logic — the server stays a thin adapter.

### Locked decisions
1. **Scheduling in the app layer**, not the server. The agent drives the existing tool on a timer.
2. **Reuse the Day-17 tool** `get_recent_commits(owner, repo, limit?)` unchanged in behavior.
3. **Separate run mode** — a NEW entry point distinct from the REPL, mirroring how `:mcp` separates
   server vs. client-demo entry points.
4. **Scheduler = a Kotlin coroutine loop** `while (isActive) { tick(); delay(interval) }`. Interval
   configurable (env, default 60s for the demo). Clean shutdown on cancel (`delay` is cancellable).
5. **Persistence = a JSON file** (kotlinx.serialization) in a separate, gitignored `digest/` dir.
   Stores last-seen SHAs + session counters. Reloaded each tick → source of truth. No SQLite.
6. **Aggregated summary = delta + stats**, computed in **deterministic Kotlin** (no LLM per tick →
   digest mode needs the MCP connection only, no API key): new this tick, total tracked, most active
   author; "no changes" + current aggregates when nothing is new.
7. **Commit identity = short SHA** (enabled by the one server edit).
8. **Reuse Day-17 plumbing** — same `SdkMcpClient` + `HttpClientTransportFactory` + `callTool` +
   `textOrError`; the MCP client is not rebuilt.

### Architecture (new package `org.example.digest` in `:app`)
```
DigestMain (runBlocking)   ← NEW entry point (mainClass org.example.digest.DigestMainKt)
  └─ SdkMcpClient(HttpClientTransportFactory(url)).connect()      ← reused Day-17 plumbing
  └─ DigestScheduler.run():  while (isActive) { tick(); delay(interval) }
        tick():  CommitCollector.collect() ─► McpClient.callTool("get_recent_commits", …)
                 DigestAggregator.apply(prevState, collected)  ← pure delta + stats
                 DigestStore.save(newState)                    ─► digest/state.json
                 emit(summary)
```
- `CollectedCommit` — app-side commit model (decoupled from `:mcp`'s `Commit`).
- `CommitCollector` — the ONE parse point: calls the tool, parses `- {sha7} {msg} — {author} (date)`
  lines; never throws (errors → empty list, logged).
- `DigestState` (`@Serializable`) — `seenShas`, `totalTracked`, `authorCounts`, `ticks`.
- `DigestStore` — JSON load/save; missing/corrupt → empty state.
- `DigestAggregator` — pure `apply(previous, collected) → TickResult(state, newCommits, summary)`.
- `DigestScheduler` — `tick()` (testable, off the timing loop) + `run()` (the loop).

**Run order:** `./gradlew :mcp:run` (HTTP server), then `./gradlew :app:runDigest`. Config via env:
`MCP_SERVER_URL`, `DIGEST_OWNER`, `DIGEST_REPO`, `DIGEST_LIMIT`, `DIGEST_INTERVAL_SECONDS`. The
interactive REPL is unchanged (`./gradlew :app:run`).

**Tests (hand-written fakes):** `DigestAggregatorTest` (delta + stats), `DigestStoreTest` (JSON
round-trip), `CommitCollectorTest` (parsing via a fake `McpClient`), `DigestSchedulerTest`
(consecutive ticks, restart reload, clean cancellation).

---

## Day 19 — MCP tool composition (a 3-tool pipeline) — PLANNED

Day 19 proves the LLM **composes a chain of MCP tools**: from one natural-language request it calls
*get → process → save* in sequence, passing data between them, with **no hardcoded sequence in the
agent**. All three tools live on **our one MCP server**; the server stays a **thin adapter with NO
LLM** (the "process" step is a deterministic report/итог, not LLM summarization).

**Agent side = no code changes.** The Day-17 `AgenticLoop` already advertises every server tool and
chains tool calls, feeding each result back as a `tool_result` block so the LLM forms the next tool's
arguments from the previous tool's output. A 3-tool chain + final answer = 4 turns ≤
`DEFAULT_MAX_ITERATIONS = 5`, so the loop is unchanged. Data flows **tool → LLM context → next tool**
— the LLM threads it; nothing is wired in code.

**Server side = two NEW deterministic tools via the Day-17 registry** — `build_commit_report(commits)`
(groups/counts/themes the commit text) and `save_to_file(filename, content)` (sanitized, protected
write). `get_recent_commits` is reused unchanged. **Full design, locked decisions, and verification
live in [`mcp/PLAN.md`](mcp/PLAN.md) (Day 19 section)** — the new work is all server-side.
