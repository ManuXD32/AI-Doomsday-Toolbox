package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AgentLocalWorkspaceSupportTest {
    @Test
    fun pythonDefaultsToConsoleAndWebDefaultsToWeb() {
        val python = AgentRunConfigParser.parse(
            """{"version":1,"runtime":"python","entrypoint":"main.py"}"""
        )
        val web = AgentRunConfigParser.parse(
            """{"version":1,"runtime":"web","entrypoint":"index.html"}"""
        )

        assertEquals(AgentLocalRuntimeType.PYTHON, python.runtime)
        assertEquals(AgentRunUiMode.CONSOLE, python.uiMode)
        assertEquals(AgentLocalRuntimeType.WEB, web.runtime)
        assertEquals(AgentRunUiMode.WEB, web.uiMode)
    }

    @Test
    fun pythonWebManifestIsRejectedWithStableMismatchError() {
        assertMismatch(
            """{"version":1,"runtime":"python","entrypoint":"main.py","ui":"web"}""",
            "RUN_CONFIG_RUNTIME_UI_MISMATCH: runtime python requires ui console."
        )
    }

    @Test
    fun webConsoleManifestIsRejectedWithStableMismatchError() {
        assertMismatch(
            """{"version":1,"runtime":"web","entrypoint":"index.html","ui":"console"}""",
            "RUN_CONFIG_RUNTIME_UI_MISMATCH: runtime web requires ui web."
        )
    }

    private fun assertMismatch(raw: String, expected: String) {
        try {
            AgentRunConfigParser.parse(raw)
            fail("Expected runtime/ui mismatch")
        } catch (error: IllegalArgumentException) {
            assertEquals(expected, error.message)
        }
    }
}
