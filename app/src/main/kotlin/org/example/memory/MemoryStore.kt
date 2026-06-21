package org.example.memory

import java.io.File

/**
 * Facade over the memory layers (Days 11–14). Injected into the Agent (for
 * system-prompt assembly + working-memory overwrite) and the REPL (for the
 * task/remember/profile/invariant commands).
 *
 *   short-term — in-memory session dialog        -> goes into `messages`
 *   long-term  — profile + knowledge, on files   -> system prompt (manual)
 *   working    — active task, on files           -> system prompt (auto-extracted)
 *   invariants — global hard constraints, on file -> system prompt (first, Day 14)
 *
 * Everything on files lives under [root] (gitignored). Subdirectories/templates
 * are created on construction, so the store is always usable. The invariants file
 * is created lazily (missing = no invariants).
 */
class MemoryStore(root: File = File("memory")) {

    val shortTerm: ShortTermMemory = ShortTermMemory()
    val longTerm: LongTermMemory = LongTermMemory(File(root, "long-term"))
    val working: WorkingMemory = WorkingMemory(File(root, "working"))
    val invariants: InvariantStore = InvariantStore(File(root, "invariants.md"))
}
