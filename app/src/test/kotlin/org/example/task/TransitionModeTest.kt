package org.example.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransitionModeTest {

    @Test
    fun `the default mode is CONFIRM`() {
        assertEquals(TransitionMode.CONFIRM, TransitionMode.DEFAULT)
    }

    @Test
    fun `parse accepts auto and confirm, case-insensitively`() {
        assertEquals(TransitionMode.AUTO, TransitionMode.parse("auto"))
        assertEquals(TransitionMode.CONFIRM, TransitionMode.parse("confirm"))
        assertEquals(TransitionMode.AUTO, TransitionMode.parse("  AUTO "))
    }

    @Test
    fun `parse returns null for invalid or empty input`() {
        assertNull(TransitionMode.parse("manual"))
        assertNull(TransitionMode.parse(""))
        assertNull(TransitionMode.parse(null))
    }
}
