package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDirectPolicyTest {
    @Test
    fun `direct profile uses 16K context and thinking disabled`() {
        val policy = AgentHarnessPolicy.forProfile(AgentHarnessProfile.DIRECT)

        assertTrue(policy.isDirect)
        assertEquals(16_384, policy.defaultContextTokens)
        assertEquals(2_048, policy.maxOutputTokens(AgentHarnessStage.CONTROL))
        assertEquals(4_096, policy.maxOutputTokens(AgentHarnessStage.BUILD))
        assertEquals(false, policy.thinkingOverride)
        assertTrue(policy.optimizedSystemPrompt.orEmpty().length / 4 < AgentHarnessPolicy.DIRECT_STABLE_PREFIX_MAX_TOKENS)
    }

    @Test
    fun `direct stable prompt and schema do not change across phases`() {
        val prompt = AgentHarnessPolicy.directSystemPrompt()
        assertTrue(prompt.contains("one direct project agent"))
        assertFalse(prompt.contains("call_agent"))
        assertFalse(prompt.contains("specialist"))
        assertFalse(prompt.contains("tools_reference"))
        assertTrue(prompt.contains("choose implementation algorithms"))
        assertTrue(prompt.contains("treat project inspection as complete"))
        assertTrue(prompt.contains("Research is not a prerequisite"))
        assertTrue(prompt.length / 4 < AgentHarnessPolicy.DIRECT_STABLE_PREFIX_MAX_TOKENS)

        val tools = listOf(
            directTool("read_file"),
            directTool("list_directory"),
            directTool("search_code"),
            directTool("write_file"),
            directTool("edit_file"),
            directTool("question"),
            directTool("finish_task"),
            directTool("tool_help"),
            directTool("run_project"),
            directTool("observe_preview"),
            directTool("interact_preview"),
            directTool("call_agent")
        )
        val plan = AgentToolSchemaPolicy.selectDirectToolPalette(tools, AgentDirectBackend.LOCAL)
        val build = AgentToolSchemaPolicy.selectDirectToolPalette(tools, AgentDirectBackend.LOCAL)
        val verify = AgentToolSchemaPolicy.selectDirectToolPalette(tools, AgentDirectBackend.LOCAL)
        assertEquals(plan.map { it.name }, build.map { it.name })
        assertEquals(build.map { it.name }, verify.map { it.name })
        assertFalse(plan.any { it.name == "call_agent" })
        assertEquals(
            AgentHarnessPolicy.DIRECT_CORE_TOOL_NAMES +
                AgentToolSchemaPolicy.DIRECT_LOCAL_TOOL_NAMES,
            plan.map { it.name }.toSet()
        )
    }

    @Test
    fun `remote direct palette adds run command and only one optional activation`() {
        val tools = listOf(
            directTool("read_file"),
            directTool("write_file"),
            directTool("edit_file"),
            directTool("question"),
            directTool("finish_task"),
            directTool("tool_help"),
            directTool("run_command"),
            directTool("search_web"),
            directTool("generate_image"),
            directTool("check_command")
        )
        val core = AgentToolSchemaPolicy.selectDirectToolPalette(tools, AgentDirectBackend.REMOTE)
        assertTrue(core.map { it.name }.contains("run_command"))
        assertFalse(core.map { it.name }.contains("search_web"))
        assertFalse(core.map { it.name }.contains("check_command"))

        val activated = AgentToolSchemaPolicy.selectDirectToolPalette(
            tools,
            AgentDirectBackend.REMOTE,
            activatedTool = "search_web",
            pendingStatusTool = "check_command"
        )
        assertTrue(activated.map { it.name }.containsAll(listOf("search_web", "check_command")))
        assertEquals(core.map { it.name }.toSet() + setOf("search_web", "check_command"), activated.map { it.name }.toSet())
    }

    @Test
    fun `proot direct palette combines command and preview tools`() {
        val tools = listOf(
            directTool("read_file"),
            directTool("run_command"),
            directTool("run_project"),
            directTool("observe_preview"),
            directTool("interact_preview")
        )

        val names = AgentToolSchemaPolicy.selectDirectToolPalette(
            tools,
            AgentDirectBackend.PROOT
        ).map { it.name }.toSet()

        assertTrue("run_command" in names)
        assertTrue("run_project" in names)
        assertTrue("observe_preview" in names)
        assertTrue("interact_preview" in names)
        assertEquals(AgentDirectBackend.PROOT, AgentDirectBackend.fromStored("LOCAL_PROOT"))
    }

    @Test
    fun `remote pending status schema is constrained to active command`() {
        val selected = AgentToolSchemaPolicy.selectDirectToolPalette(
            tools = listOf(directTool("check_command")),
            backend = AgentDirectBackend.REMOTE,
            pendingStatusTool = "check_command",
            pendingStatusHandle = "cmd-7"
        ).single()

        val schema = selected.schemaJson.orEmpty()
        assertTrue(schema.contains("\"const\":\"cmd-7\""))
        assertTrue(schema.contains("\"additionalProperties\":false"))
        assertFalse(schema.contains("cmd-8"))
    }

    @Test
    fun `direct context budget uses exact count or conservative fallback`() {
        assertEquals(10, AgentHarnessPolicy.directInputTokenCount("a".repeat(32), exactTokenCount = 10))
        assertEquals(10, AgentHarnessPolicy.directInputTokenCount("a".repeat(32)))
        assertTrue(AgentHarnessPolicy.directFitsContext(15_000, 800))
        assertFalse(AgentHarnessPolicy.directFitsContext(15_100, 800))
        assertEquals(15_872, AgentHarnessPolicy.directInputBudget(16_384, 0))
        assertNotEquals(
            AgentHarnessPolicy.DIRECT_DEFAULT_CONTEXT_TOKENS,
            AgentHarnessPolicy.DEFAULT_CONTEXT_TOKENS
        )
    }

    @Test
    fun `direct questions keep model-owned choices out of the user workflow`() {
        assertTrue(
            isDirectModelOwnedChoiceQuestion(
                "Which research area should I focus on first? Prime algorithms or web UI patterns."
            )
        )
        assertTrue(
            isDirectModelOwnedChoiceQuestion(
                "Which technical approach should I use: segmented sieve or Miller-Rabin?"
            )
        )
        assertTrue(
            isDirectModelOwnedChoiceQuestion(
                "Please provide authoritative information on prime algorithms and web concurrency."
            )
        )
        assertTrue(
            isDirectModelOwnedChoiceQuestion(
                "Proceed with verification: run node logic verification or inspect web preview DOM."
            )
        )
        assertFalse(
            isDirectModelOwnedChoiceQuestion(
                "May I overwrite existing production credentials?"
            )
        )
        assertFalse(
            isDirectModelOwnedChoiceQuestion(
                "What maximum numeric range must the product support?"
            )
        )
        assertTrue(containsDirectNoMoreQuestionsDirective("Do not ask questions or research."))
        assertTrue(containsDirectNoMoreQuestionsDirective("No hagas más preguntas."))
        assertEquals(
            "This action is already authorized by the approved plan. Perform every applicable planned build or verification action with the available tools now. Do not ask the user for permission to proceed or to choose between planned checks.",
            directModelOwnedChoiceRecovery(AgentHarnessPhase.VERIFY)
        )
        assertTrue(directOutputLimitRecovery(AgentHarnessPhase.BUILD).contains("below 2 KiB"))
        assertTrue(directOutputLimitRecovery(AgentHarnessPhase.BUILD).contains("DIRECT-EXTEND"))
        assertEquals(
            "write_file",
            directStrictRecoveryToolFromExactNextAction("create index.html with write_file")
        )
        assertEquals(
            "edit_file",
            directStrictRecoveryToolFromExactNextAction("Next: call edit_file once.")
        )
        assertEquals(null, directStrictRecoveryToolFromExactNextAction("continue the next step"))
        assertEquals(5 * 1_024, AgentHarnessPolicy.DIRECT_WRITE_FILE_MAX_BYTES)
    }

    @Test
    fun `plain prose build recovery constrains the retry to the exact next tool`() {
        assertEquals(
            "write_file",
            directStructuredBoundaryRecoveryTool(
                AgentHarnessPhase.BUILD,
                "Create index.html now with write_file."
            )
        )
        assertEquals(
            "observe_preview",
            directStructuredBoundaryRecoveryTool(
                AgentHarnessPhase.VERIFY,
                "Call observe_preview for the active run."
            )
        )
        assertEquals(
            null,
            directStructuredBoundaryRecoveryTool(
                AgentHarnessPhase.PLAN,
                "Use question only if the user owns a blocker."
            )
        )
        assertEquals(
            "read_file",
            directStructuredBoundaryRecoveryTool(
                phase = AgentHarnessPhase.BUILD,
                exactNextAction = "repair the latest edit_file failure",
                latestFailure = "edit_file failed: EXACT_EDIT_NO_MATCH",
                failedTool = "edit_file"
            )
        )
    }

    @Test
    fun `direct provider batches commit only the first proposal`() {
        val selection = selectDirectSerializedAction(listOf("search-a", "search-b", "fetch-c"))

        assertEquals("search-a", selection.selected)
        assertEquals(listOf("search-b", "fetch-c"), selection.deferred)
        assertEquals(null, selectDirectSerializedAction(emptyList<String>()).selected)
    }

    @Test
    fun `strict recovery activates web research only for plan misclassification`() {
        assertTrue(
            shouldActivateDirectWebResearchRepair(
                AgentHarnessPhase.PLAN,
                "search_code"
            )
        )
        assertTrue(
            shouldActivateDirectWebResearchRepair(
                AgentHarnessPhase.PLAN,
                "question",
                "Which research area should I use?"
            )
        )
        assertFalse(
            shouldActivateDirectWebResearchRepair(
                AgentHarnessPhase.BUILD,
                "search_code"
            )
        )
    }

    @Test
    fun `direct cache key omits phase and role but includes invalidation inputs`() {
        val first = AgentHarnessPolicy.directPromptCacheKey(
            conversationId = 7,
            backend = "LOCAL_SANDBOX",
            model = "spark-4b",
            endpointGeneration = "generation-1",
            coreSchemaHash = "schema-a",
            projectContextHash = "project-a"
        )
        val same = AgentHarnessPolicy.directPromptCacheKey(
            conversationId = 7,
            backend = "LOCAL_SANDBOX",
            model = "spark-4b",
            endpointGeneration = "generation-1",
            coreSchemaHash = "schema-a",
            projectContextHash = "project-a"
        )
        assertEquals(first, same)
        assertTrue(first.startsWith("direct-cache|"))
        assertTrue(first.contains("conversation=7"))
        assertNotEquals(first, first.replace("schema-a", "schema-b"))
    }

    private fun directTool(name: String): AgentTool = AgentTool(
        name = name,
        description = "$name fixture",
        parameters = emptyMap()
    )
}
