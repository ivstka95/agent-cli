package org.example.compare

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [Day 28] One entry in the local-vs-cloud eval set: an English question about THIS codebase plus its
 * [difficulty] (`simple` fact / `medium` mechanism / `hard` synthesis). English on purpose — the corpus
 * (code + English README/PLAN) is English, and cross-language retrieval is weak. Kept to 3 questions
 * because the local model is slow on RAG.
 */
@Serializable
data class CompareQuestion(
    val difficulty: String,
    val question: String,
) {
    companion object {
        /** Classpath location of the bundled eval set. */
        const val RESOURCE = "/rag-eval/local-vs-cloud-questions.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Loads the eval set from the bundled JSON resource. */
        fun load(): List<CompareQuestion> {
            val stream = CompareQuestion::class.java.getResourceAsStream(RESOURCE)
                ?: error("Eval set resource not found on classpath: $RESOURCE")
            val text = stream.bufferedReader().use { it.readText() }
            return json.decodeFromString(text)
        }
    }
}
