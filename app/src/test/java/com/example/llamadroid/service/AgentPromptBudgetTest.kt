package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptBudgetTest {
    @Test
    fun `exact and fallback capacities keep different safety reserves`() {
        val exact = resolveAgentPromptCapacity(
            configuredContextTokens = 16_384,
            reportedContextTokens = 16_896,
            exactCountingAvailable = true
        )
        val fallback = resolveAgentPromptCapacity(
            configuredContextTokens = 16_384,
            reportedContextTokens = 16_896,
            exactCountingAvailable = false
        )

        assertEquals(16_384, exact.contextCapacityTokens)
        assertEquals(256, exact.safetyReserveTokens)
        assertEquals(1_638, fallback.safetyReserveTokens)
        assertTrue(exact.maximumInputTokens > fallback.maximumInputTokens)
    }

    @Test
    fun `calibration is applied exactly once`() {
        val calibration = AgentPromptCalibration(conservativeFactor = 2.0)
        assertEquals(8_000, applyAgentPromptCalibration(4_000, calibration))
    }

    @Test
    fun `calibration learns from the uncalibrated request estimate`() {
        val updated = updateAgentPromptCalibration(
            existing = AgentPromptCalibration(),
            rawSerializedRequestTokens = 4_000,
            actualInputTokens = 8_000
        )

        assertTrue(updated.conservativeFactor in 2.05..2.07)
        assertEquals(1, updated.sampleCount)
    }

    @Test
    fun `output budget refuses a useless one-token request`() {
        val capacity = resolveAgentPromptCapacity(
            configuredContextTokens = 8_192,
            reportedContextTokens = 8_192,
            exactCountingAvailable = true
        )
        val budget = resolveAgentPromptOutputBudget(
            configuredMaxOutputTokens = 8_096,
            capacity = capacity,
            authoritativeInputTokens = 7_500
        )

        assertFalse(budget.canSend)
        assertTrue(
            budget.effectiveMaxOutputTokens < budget.minimumUsefulOutputTokens
        )
    }

    @Test
    fun `compact profile target is actually fifty percent`() {
        val limits = resolveAgentPromptPackingLimits(
            maximumInputTokens = 8_000,
            softTargetRatio = 0.50,
            compactMode = false
        )

        assertEquals(4_000, limits.targetTokens)
        assertEquals(5_200, limits.triggerTokens)
    }

    @Test
    fun `tool schema contributes to the request estimate`() {
        val tools = listOf(
            AgentTool(
                name = "read_file",
                description = "Read a file from the project.",
                parameters = mapOf("path" to "Project-relative path"),
                requiredParams = listOf("path")
            )
        )

        assertTrue(estimateRawAgentToolSchemaTokens(tools) > 10)
        assertTrue(canonicalAgentToolSchemaJson(tools).contains("read_file"))
    }

    @Test
    fun `fallback request reserves multimodal prompt space`() {
        val messages = listOf(
            OllamaService.ChatMessage(
                role = "user",
                content = "Describe this",
                imagePath = "/tmp/image.png"
            )
        )

        assertEquals(1_024, estimateFallbackMultimodalPromptTokens(messages))
    }

    @Test
    fun `assistant tool call and matching tool result form one atomic unit`() {
        val call = OllamaService.ToolCall(
            name = "read_file",
            arguments = mapOf("path" to "src/Main.kt"),
            id = "call_1"
        )
        val messages = listOf(
            AgentService.Companion.ChatMessage(
                role = "user",
                content = "Inspect the file"
            ),
            AgentService.Companion.ChatMessage(
                role = "assistant",
                content = "",
                pendingToolCall = call,
                toolCallId = "call_1",
                toolName = "read_file"
            ),
            AgentService.Companion.ChatMessage(
                role = "tool",
                content = "File: src/Main.kt",
                toolCallId = "call_1",
                toolName = "read_file"
            )
        )

        val units = buildAgentPromptAtomicUnits(messages)
        assertEquals(2, units.size)
        assertTrue(units.last().isToolExchange)
        assertEquals(2, units.last().messages.size)
    }

    @Test
    fun `hard compaction metadata round trips without losing boundaries`() {
        val original = AgentHardCompactionMetadata(
            conversationId = 42L,
            sourceSnapshotEndSequence = 120,
            sourceTurnGroupCount = 9,
            contextTokens = 16_384,
            maximumInputTokens = 15_104,
            requiredPrimacyTokens = 3_000,
            profileName = "coder",
            toolDefinitionsHash = "abc",
            summaryHash = "def",
            createdAt = 1234L
        )

        assertEquals(
            original,
            AgentHardCompactionMetadata.fromJson(original.toJson())
        )
    }

    @Test
    fun `llama input token parser accepts current response layouts`() {
        assertEquals(
            9_216,
            parseLlamaInputTokenCountBody("""{"input_tokens":9216}""")
        )
        assertEquals(
            8_000,
            parseLlamaInputTokenCountBody(
                """{"usage":{"prompt_tokens":8000}}"""
            )
        )
        assertEquals(
            7_777,
            parseLlamaInputTokenCountBody("""{"tokens_count":7777}""")
        )
    }
}
