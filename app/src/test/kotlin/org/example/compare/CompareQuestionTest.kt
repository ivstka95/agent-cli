package org.example.compare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompareQuestionTest {

    @Test
    fun `eval set loads 3 well-formed questions of increasing difficulty`() {
        val questions = CompareQuestion.load()

        assertEquals(3, questions.size)
        assertEquals(listOf("simple", "medium", "hard"), questions.map { it.difficulty })
        questions.forEach { q ->
            assertTrue(q.question.isNotBlank(), "question must not be blank")
            assertTrue(q.question.trim().endsWith("?"), "question should be phrased as a question: ${q.question}")
        }
    }
}
