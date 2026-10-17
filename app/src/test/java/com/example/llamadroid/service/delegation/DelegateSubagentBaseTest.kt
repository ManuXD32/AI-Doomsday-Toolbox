package com.example.llamadroid.service.delegation

import com.example.llamadroid.service.AgentTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegateSubagentBaseTest {
    private val tools = listOf(AgentTool("a", "desc", mapOf("x" to "y"), listOf("x")))

    @Test fun hashIsStableAndSensitiveToEveryInput() {
        val base = DelegateSubagentBase.hash("http://127.0.0.1:8080", "sys", tools, true)
        assertEquals(base, DelegateSubagentBase.hash("http://127.0.0.1:8080", "sys ", tools, true))
        assertNotEquals(base, DelegateSubagentBase.hash("http://127.0.0.1:8080", "sys2", tools, true))
        assertNotEquals(base, DelegateSubagentBase.hash("http://127.0.0.1:8080", "sys", emptyList(), true))
        assertNotEquals(base, DelegateSubagentBase.hash("http://127.0.0.1:8080", "sys", tools, false))
    }

    @Test fun hintUsesDelegateScopeAndBuilder() {
        val hint = DelegateSubagentBase.hint("http://127.0.0.1:8080", "m.gguf", true)
        assertNotNull(hint)
        assertTrue(hint!!.key.fileStem.startsWith("base-delegate-"))
        assertNotNull(hint.builder)
    }

    @Test fun thinkingDefaultsOnAndReadsServerParam() {
        assertTrue(DelegateSubagentBase.thinkingFor(null))
        assertEquals(false, DelegateSubagentBase.thinkingFor("{\"enable_thinking\":false}"))
    }
}
