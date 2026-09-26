package com.example.llamadroid.ui.agent

import com.example.llamadroid.data.db.AgentRuntimeBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDirectUiSupportTest {
    @Test
    fun directDefaultsMatchReleaseContract() {
        assertTrue(AgentDirectUiDefaults.CONTEXT_TOKENS == 16_384)
        assertTrue(AgentDirectUiDefaults.PLAN_OUTPUT_TOKENS == 2_048)
        assertTrue(AgentDirectUiDefaults.BUILD_OUTPUT_TOKENS == 4_096)
        assertTrue(AgentDirectUiDefaults.SAFETY_RESERVE_TOKENS == 512)
        assertFalse(AgentDirectUiDefaults.THINKING_ENABLED)
    }

    @Test
    fun budgetRuleIncludesSafetyReserve() {
        assertTrue(
            directAgentBudgetFits(
                inputTokens = 13_824,
                reservedOutputTokens = 2_048
            )
        )
        assertFalse(
            directAgentBudgetFits(
                inputTokens = 13_825,
                reservedOutputTokens = 2_048
            )
        )
        assertFalse(directAgentBudgetFits(inputTokens = -1, reservedOutputTokens = 1))
    }

    @Test
    fun directModelOptionsFollowTheSelectedBackend() {
        val ollama = listOf("qwen3.5:9b")
        val openAi = listOf("Spark-X2.5-4B", "Ling-3.0-tiny")

        assertEquals(
            listOf("qwen3.5:9b", "saved-ollama"),
            directAgentModelOptions(
                backend = AgentRuntimeBackend.OLLAMA,
                ollamaModels = ollama,
                openAiModels = openAi,
                selectedModel = "saved-ollama"
            )
        )
        assertEquals(
            listOf("Spark-X2.5-4B", "Ling-3.0-tiny", "saved-server"),
            directAgentModelOptions(
                backend = AgentRuntimeBackend.LLAMA_SERVER,
                ollamaModels = ollama,
                openAiModels = openAi,
                selectedModel = "saved-server"
            )
        )
        assertEquals(
            listOf("Spark-X2.5-4B", "Ling-3.0-tiny"),
            directAgentModelOptions(
                backend = AgentRuntimeBackend.LLAMA_SWAP,
                ollamaModels = ollama,
                openAiModels = openAi,
                selectedModel = "Spark-X2.5-4B"
            )
        )
    }
}
