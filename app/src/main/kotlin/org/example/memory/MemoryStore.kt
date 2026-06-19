package org.example.memory

import java.io.File

/**
 * Facade over the three memory layers (Day 11). Injected into the Agent (for
 * system-prompt assembly + working-memory overwrite) and the REPL (for the
 * task/remember commands).
 *
 *   short-term — in-memory session dialog        -> goes into `messages`
 *   long-term  — profile + knowledge, on files   -> system prompt (manual)
 *   working    — active task, on files           -> system prompt (auto-extracted)
 *
 * Everything on files lives under [root] (gitignored). Subdirectories/templates
 * are created on construction, so the store is always usable.
 */
class MemoryStore(root: File = File("memory")) {

    val shortTerm: ShortTermMemory = ShortTermMemory()
    val longTerm: LongTermMemory = LongTermMemory(File(root, "long-term"))
    val working: WorkingMemory = WorkingMemory(File(root, "working"))
}
