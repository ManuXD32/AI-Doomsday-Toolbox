package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkflowModelsTest {
    @Test
    fun `canonical tool call preserves endpoint id and raw arguments bytes`() {
        val raw = """{ "path" : "src/main.kt", "line" : 7 }"""
        val message = OllamaService.ChatMessage(
            role = "assistant",
            content = "",
            toolCalls = listOf(
                OllamaService.ToolCall(
                    name = "read_file",
                    arguments = mapOf("path" to "src/main.kt", "line" to "7"),
                    id = "endpoint_call_123",
                    rawArgumentsJson = raw
                )
            )
        ).toCanonicalInferenceMessage()

        assertEquals("endpoint_call_123", message.toolCalls.single().id)
        assertEquals(raw, message.toolCalls.single().rawArgumentsJson)
        assertTrue(message.toStableJson().contains("\"endpoint_call_123\""))
        assertTrue(message.toStableJson().contains("\\\"path\\\""))
    }

    @Test
    fun `canonical tools are stable across input and schema property order`() {
        val first = AgentTool(
            name = "zeta",
            description = "Z",
            parameters = mapOf("b" to "B", "a" to "A"),
            requiredParams = listOf("b", "a"),
            schemaJson = """{"properties":{"b":{"type":"string"},"a":{"type":"string"}},"type":"object"}"""
        )
        val second = AgentTool(
            name = "alpha",
            description = "A",
            parameters = mapOf("x" to "X")
        )
        val reorderedFirst = first.copy(
            parameters = mapOf("a" to "A", "b" to "B"),
            requiredParams = listOf("a", "b"),
            schemaJson = """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"string"}}}"""
        )

        assertEquals(
            canonicalToolsJson(listOf(first, second)),
            canonicalToolsJson(listOf(second, reorderedFirst))
        )
    }

    @Test
    fun `compaction tail uses quarter of usable context with clamps`() {
        val small = resolveContextCompactionBudget(
            modelContextTokens = 8_192,
            outputTokens = 2_000,
            pinnedPromptTokens = 1_000
        )
        val large = resolveContextCompactionBudget(
            modelContextTokens = 65_536,
            outputTokens = 8_096,
            pinnedPromptTokens = 4_000
        )

        assertTrue(small.recentTailTargetTokens in 1_000..2_000)
        assertTrue(large.recentTailTargetTokens in 2_000..8_000)
        assertTrue(large.usableContextTokens > small.usableContextTokens)
    }

    @Test
    fun `prompt cache comparison reports exact changed component`() {
        val base = turnContext(parameters = """{"temperature":0.7}""")
        val changed = turnContext(parameters = """{"temperature":0.8}""")
        val result = comparePromptCacheState(base, changed)

        assertFalse(result.previousPrefixCompatible ?: true)
        assertEquals(setOf("parameters"), result.changedComponents)
    }

    @Test
    fun `structured questions allow up to five questions and at most three choices per question`() {
        val valid = QuestionSpec(
            questions = listOf(
                QuestionItem(
                    id = "runtime",
                    header = "Runtime",
                    prompt = "Which runtime should be used?",
                    options = listOf(
                        QuestionOption("a", "A"),
                        QuestionOption("b", "B"),
                        QuestionOption("c", "C")
                    )
                )
            )
        )

        assertEquals(3, valid.questions.single().options.size)
        val invalid = runCatching {
            valid.copy(
                questions = listOf(
                    valid.questions.single().copy(
                        options = valid.questions.single().options + QuestionOption("d", "D")
                    )
                )
            )
        }
        assertTrue(invalid.isFailure)
        assertEquals(
            5,
            QuestionSpec(questions = List(5) { index ->
                QuestionItem(
                    id = "q$index",
                    header = "Question",
                    prompt = "Choose a target",
                    options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B"))
                )
            }).questions.size
        )
    }

    @Test
    fun `structured questions reject plan approval prompts`() {
        val result = runCatching {
            QuestionSpec(
                questions = listOf(
                    QuestionItem(
                        id = "approval",
                        header = "Plan approval",
                        prompt = "Do you approve this plan?",
                        options = listOf(QuestionOption("yes", "Yes"), QuestionOption("no", "No"))
                    )
                )
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `structured questions reject duplicate literal alternatives`() {
        val result = runCatching {
            QuestionSpec(
                questions = listOf(
                    QuestionItem(
                        id = "target",
                        header = "Target",
                        prompt = "Choose a target",
                        options = listOf(
                            QuestionOption("same", "Android"),
                            QuestionOption("same", "Android")
                        )
                    )
                )
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `structured questions reject question-shaped alternatives and combined prompts`() {
        val questionShapedOption = runCatching {
            QuestionSpec(
                listOf(
                    QuestionItem(
                        id = "runtime",
                        header = "Runtime",
                        prompt = "Choose a runtime",
                        options = listOf(
                            QuestionOption("web", "Which web framework?"),
                            QuestionOption("native", "Native Android")
                        )
                    )
                )
            )
        }
        val combinedPrompts = runCatching {
            QuestionSpec(
                listOf(
                    QuestionItem(
                        id = "combined",
                        header = "Targets",
                        prompt = "Which runtime? Which visual style?",
                        options = listOf(
                            QuestionOption("web", "Web"),
                            QuestionOption("native", "Native Android")
                        )
                    )
                )
            )
        }

        assertTrue(questionShapedOption.isFailure)
        assertTrue(combinedPrompts.isFailure)
    }

    @Test
    fun `submitted question answers become immutable human-readable user requirements`() {
        val spec = QuestionSpec(
            listOf(
                QuestionItem(
                    id = "platform",
                    header = "Platform",
                    prompt = "Which platform should be targeted?",
                    options = listOf(
                        QuestionOption("android", "Android"),
                        QuestionOption("desktop", "Desktop")
                    )
                )
            )
        )
        val result = authoritativeQuestionAnswerJson(
            spec.toJson(),
            """{"answers":{"platform":{"selected":["android"],"custom":"Use Compose"}}}"""
        )

        assertTrue(result.contains("critical_user_requirements"))
        assertTrue(result.contains("Which platform should be targeted?"))
        assertTrue(result.contains("Android"))
        assertTrue(result.contains("Use Compose"))
    }

    @Test
    fun `plan mode blocks build tools even when a stale tool call reaches runtime`() {
        assertTrue(isPlanModeToolBlocked(planModeEnabled = true, toolName = "write_file"))
        assertTrue(isPlanModeToolBlocked(planModeEnabled = true, toolName = "run_command"))
        assertTrue(isPlanModeToolBlocked(planModeEnabled = true, toolName = "call_agent"))
        assertTrue(isPlanModeToolBlocked(planModeEnabled = true, toolName = "run_skill_script"))
        assertFalse(isPlanModeToolBlocked(planModeEnabled = true, toolName = "question"))
        assertFalse(isPlanModeToolBlocked(planModeEnabled = true, toolName = "propose_plan"))
        assertFalse(isPlanModeToolBlocked(planModeEnabled = false, toolName = "write_file"))
    }

    @Test
    fun `plan mode keeps clarification and plan proposal tools available`() {
        assertTrue("question" in PLAN_MODE_ALLOWED_TOOL_NAMES)
        assertTrue("todo_write" in PLAN_MODE_ALLOWED_TOOL_NAMES)
        assertTrue("propose_plan" in PLAN_MODE_ALLOWED_TOOL_NAMES)
        assertFalse("write_file" in PLAN_MODE_ALLOWED_TOOL_NAMES)
        assertFalse("apply_patch" in PLAN_MODE_ALLOWED_TOOL_NAMES)
    }

    @Test
    fun `plan proposal requires one answered structured question`() {
        assertFalse(isPlanQuestionRequirementSatisfied(planModeEnabled = true, answeredQuestionCount = 0))
        assertTrue(isPlanQuestionRequirementSatisfied(planModeEnabled = true, answeredQuestionCount = 1))
        assertTrue(isPlanQuestionRequirementSatisfied(planModeEnabled = false, answeredQuestionCount = 0))
    }

    private fun turnContext(parameters: String) = AgentTurnContext(
        rootTurnId = "root",
        conversationId = 1,
        agentKey = "ORCHESTRATOR",
        backend = "llama-server",
        modelLabel = "model.gguf",
        endpointGeneration = "server-1",
        contextTokens = 16_384,
        configuredOutputTokens = 8_096,
        effectiveOutputTokens = 4_000,
        stableSystemPrompt = "Stable prompt",
        tools = emptyList(),
        skillIds = emptyList(),
        thinkingEnabled = false,
        parametersJson = parameters
    )
}
