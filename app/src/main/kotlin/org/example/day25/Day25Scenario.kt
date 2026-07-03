package org.example.day25

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [Day 25] One long verification scenario: a fixed dialogue [goal] (the task memory) plus a scripted
 * sequence of [questions] over THIS codebase. The eval seeds the goal, then runs each question through
 * the agent (grounded with RAG) accumulating history — checking the goal is retained and every answer
 * is sourced across 10–15 turns.
 */
@Serializable
data class Day25Scenario(
    val name: String,
    val goal: String,
    val questions: List<String>,
) {
    companion object {
        /** Classpath location of the bundled scenarios. */
        const val RESOURCE = "/rag-eval/day25-scenarios.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Loads the scenarios from the bundled JSON resource. */
        fun load(): List<Day25Scenario> {
            val stream = Day25Scenario::class.java.getResourceAsStream(RESOURCE)
                ?: error("Day-25 scenarios resource not found on classpath: $RESOURCE")
            val text = stream.bufferedReader().use { it.readText() }
            return json.decodeFromString(text)
        }
    }
}
