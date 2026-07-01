package org.example.ragmode

import kotlinx.serialization.json.Json

/**
 * One entry in the Day-22 evaluation set: an English question about THIS codebase, plus what a good
 * answer should contain ([expectation]) and which repo files it should ground on ([expectedSources]).
 *
 * The questions are English on purpose — the corpus (code + English README/PLAN) is English, and
 * cross-language retrieval is weak (a Russian query vector clusters near Russian text, not ours).
 */
@kotlinx.serialization.Serializable
data class ControlQuestion(
    val question: String,
    val expectation: String,
    val expectedSources: List<String>,
) {
    companion object {
        /** Classpath location of the bundled eval set. */
        const val RESOURCE = "/rag-eval/control-questions.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Loads the eval set from the bundled JSON resource. */
        fun loadEvalSet(): List<ControlQuestion> {
            val stream = ControlQuestion::class.java.getResourceAsStream(RESOURCE)
                ?: error("Eval set resource not found on classpath: $RESOURCE")
            val text = stream.bufferedReader().use { it.readText() }
            return json.decodeFromString(text)
        }
    }
}
