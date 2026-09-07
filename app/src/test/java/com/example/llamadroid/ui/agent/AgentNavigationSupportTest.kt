package com.example.llamadroid.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNavigationSupportTest {
    @Test
    fun `back pops the previous page without invoking Tools fallback`() {
        var fallbackCalls = 0

        val result = performAgentBackNavigation(
            popBackStack = { true },
            navigateToTools = { fallbackCalls++ }
        )

        assertEquals(AgentBackNavigationResult.POPPED_PREVIOUS, result)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun `back falls back to Tools when there is no previous page`() {
        var fallbackCalls = 0

        val result = performAgentBackNavigation(
            popBackStack = { false },
            navigateToTools = { fallbackCalls++ }
        )

        assertEquals(AgentBackNavigationResult.FELL_BACK_TO_TOOLS, result)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun `dialog and editor Back stay owned by their existing UI`() {
        assertFalse(
            shouldHandleAgentSystemBack(
                hasBlockingDialog = true,
                hasEditor = false
            )
        )
        assertFalse(
            shouldHandleAgentSystemBack(
                hasBlockingDialog = false,
                hasEditor = true
            )
        )
        assertTrue(
            shouldHandleAgentSystemBack(
                hasBlockingDialog = false,
                hasEditor = false
            )
        )
    }
}
