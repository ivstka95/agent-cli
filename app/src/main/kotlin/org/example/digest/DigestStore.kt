package org.example.digest

import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON persistence for [DigestState] (kotlinx.serialization). Lives in its own gitignored file,
 * separate from the agent's `memory/`. Missing or corrupt file → a fresh empty state, so the
 * daemon always starts cleanly.
 */
class DigestStore(private val file: File = File("digest/state.json")) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): DigestState {
        if (!file.exists()) return DigestState()
        return runCatching { json.decodeFromString<DigestState>(file.readText()) }
            .getOrElse { DigestState() }
    }

    fun save(state: DigestState) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(state))
    }
}
