package org.example.ragmode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlQuestionTest {

    @Test
    fun `eval set loads 10 well-formed English control questions`() {
        val questions = ControlQuestion.loadEvalSet()

        assertEquals(10, questions.size)
        questions.forEach { q ->
            assertTrue(q.question.isNotBlank(), "question must not be blank")
            assertTrue(q.question.trim().endsWith("?"), "question should be phrased as a question: ${q.question}")
            assertTrue(q.expectation.isNotBlank(), "expectation must not be blank for: ${q.question}")
            assertTrue(q.expectedSources.isNotEmpty(), "expected sources must not be empty for: ${q.question}")
        }
    }
}
