package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHarnessPolicyTest {
    @Test
    fun `profile ids normalize unknown and missing values to legacy`() {
        assertEquals(AgentHarnessProfile.OPTIMIZED, AgentHarnessProfile.fromId(" OPTIMIZED "))
        assertEquals(AgentHarnessProfile.LEGACY, AgentHarnessProfile.fromId("unknown"))
        assertEquals(AgentHarnessProfile.LEGACY, AgentHarnessProfile.fromId(null))
        assertEquals(AgentHarnessPolicy.OPTIMIZED, AgentHarnessPolicy.normalizeProfileId("optimized"))
        assertEquals(AgentHarnessPolicy.LEGACY, AgentHarnessPolicy.normalizeProfileId(""))
    }

    @Test
    fun `prompt cache key is stable within phase and distinct across phase and profile`() {
        val branch = "19:ORCHESTRATOR"
        val optimizedPlan = AgentHarnessPolicy.promptCacheKey(
            branch = branch,
            normalizedProfile = " OPTIMIZED ",
            phase = AgentHarnessPhase.PLAN
        )

        assertEquals(
            optimizedPlan,
            AgentHarnessPolicy.promptCacheKey(
                branch = branch,
                normalizedProfile = AgentHarnessPolicy.OPTIMIZED,
                phase = AgentHarnessPhase.PLAN
            )
        )
        assertNotEquals(
            optimizedPlan,
            AgentHarnessPolicy.promptCacheKey(
                branch,
                AgentHarnessPolicy.OPTIMIZED,
                AgentHarnessPhase.BUILD
            )
        )
        assertNotEquals(
            optimizedPlan,
            AgentHarnessPolicy.promptCacheKey(
                branch,
                AgentHarnessPolicy.LEGACY,
                AgentHarnessPhase.PLAN
            )
        )
        assertTrue(optimizedPlan.contains("profile=9:optimized"))
        assertTrue(optimizedPlan.endsWith("phase=PLAN"))
    }

    @Test
    fun `same root transition rebuilds cached phase prompt and tool palette`() {
        val branch = "19:ORCHESTRATOR"
        val profile = AgentHarnessPolicy.normalizeProfileId(AgentHarnessPolicy.OPTIMIZED)
        val actualTools = (
            AgentHarnessPolicy.PLAN_ROOT_TOOLS +
                AgentHarnessPolicy.BUILD_ROOT_TOOLS +
                AgentHarnessPolicy.VERIFY_ROOT_TOOLS
            )
            .distinct()
            .sorted()
            .map(::agentTool)
        val cachedPrompts = linkedMapOf<String, Pair<String, List<AgentTool>>>()

        fun cachedFor(phase: AgentHarnessPhase): Pair<String, List<AgentTool>> {
            val key = AgentHarnessPolicy.promptCacheKey(branch, profile, phase)
            return cachedPrompts.getOrPut(key) {
                AgentHarnessPolicy.optimizedSystemPromptForPhase(phase) to
                    AgentToolSchemaPolicy.selectOptimizedToolPaletteForRole(
                        tools = actualTools,
                        phase = phase,
                        role = "ORCHESTRATOR"
                    )
            }
        }

        val plan = cachedFor(AgentHarnessPhase.PLAN)
        val build = cachedFor(AgentHarnessPhase.BUILD)
        val verify = cachedFor(AgentHarnessPhase.VERIFY)

        assertTrue(plan.first.startsWith("Direct root Plan"))
        assertTrue(build.first.startsWith("Direct root Build"))
        assertTrue(verify.first.startsWith("Direct root Verify"))
        assertTrue(plan.second.map { it.name }.containsAll(listOf("propose_plan", "web_search")))
        assertFalse(plan.second.any { it.name == "write_file" })
        assertTrue(build.second.map { it.name }.containsAll(listOf("write_file", "run_project", "finish_task")))
        assertFalse(build.second.any { it.name == "propose_plan" })
        assertTrue(verify.second.map { it.name }.containsAll(listOf("run_project", "finish_task")))
        assertFalse(verify.second.any { it.name == "write_file" })
        assertEquals(3, cachedPrompts.size)

        assertEquals(plan, cachedFor(AgentHarnessPhase.PLAN))
        assertEquals(build, cachedFor(AgentHarnessPhase.BUILD))
        assertEquals(verify, cachedFor(AgentHarnessPhase.VERIFY))
        assertEquals(3, cachedPrompts.size)
    }

    @Test
    fun `optimized policy exposes small device budgets and concise prompt`() {
        val policy = AgentHarnessPolicy.forProfile(AgentHarnessProfile.OPTIMIZED)
        val prompt = policy.optimizedSystemPrompt.orEmpty()

        assertEquals(8_192, policy.defaultContextTokens)
        assertEquals(16_384, policy.maximumContextTokens)
        assertEquals(2_048, policy.maxOutputTokens(AgentHarnessStage.CONTROL))
        assertEquals(4_096, policy.maxOutputTokens(AgentHarnessStage.BUILD))
        assertEquals(512, policy.maxOutputTokens(AgentHarnessStage.SUMMARY))
        assertTrue(prompt.contains("Plan"))
        assertTrue(prompt.contains("read project_state_read only if needed"))
        assertTrue(prompt.contains("Build"))
        assertTrue(prompt.contains("at most 2 search calls total"))
        assertTrue(prompt.contains("at most 4 fetch_url calls"))
        assertTrue(prompt.contains("propose_plan"))
        assertTrue(prompt.contains("at most 500 words"))
        assertTrue(prompt.contains(".adt/run.json"))
        assertTrue(prompt.contains("run_project/check_project_run"))
        assertTrue(prompt.contains("observe_preview/interact_preview"))
        assertTrue(prompt.contains("never for preferences"))
        assertTrue(policy.allowsOptionalSequentialSpecialist("VISUAL_TESTER"))
        assertTrue(policy.allowsOptionalSequentialSpecialist("REVIEWER"))
        assertEquals(2, policy.researchLimits.maxSearchCalls)
        assertEquals(4, policy.researchLimits.maxFetchCalls)
        assertFalse(policy.researchLimits.autoSummarize)
    }

    @Test
    fun `optimized budgets preserve explicit values within the low end limits`() {
        val policy = AgentHarnessPolicy.forProfile(AgentHarnessProfile.OPTIMIZED)

        assertEquals(
            AgentHarnessPolicy.MAX_CONTEXT_TOKENS,
            policy.resolveContextTokens(
                configuredContextTokens = 32_768,
                explicit = true
            )
        )
        assertEquals(
            AgentHarnessPolicy.DEFAULT_CONTEXT_TOKENS,
            policy.resolveContextTokens(
                configuredContextTokens = 32_768,
                explicit = false
            )
        )
        assertEquals(
            AgentHarnessPolicy.BUILD_MAX_OUTPUT_TOKENS,
            policy.resolveOutputTokens(
                stage = AgentHarnessStage.BUILD,
                configuredOutputTokens = 12_345,
                explicit = true
            )
        )
        assertEquals(
            AgentHarnessPolicy.BUILD_MAX_OUTPUT_TOKENS,
            policy.resolveOutputTokens(
                stage = AgentHarnessStage.BUILD,
                configuredOutputTokens = 12_345,
                explicit = false
            )
        )
    }

    @Test
    fun `optimized phase prompts keep responsibilities compact and separate`() {
        val plan = AgentHarnessPolicy.optimizedSystemPromptForPhase(AgentHarnessPhase.PLAN)
        val build = AgentHarnessPolicy.optimizedSystemPromptForPhase(AgentHarnessPhase.BUILD)
        val verify = AgentHarnessPolicy.optimizedSystemPromptForPhase(AgentHarnessPhase.VERIFY)
        val specialistNames = AgentHarnessPolicy.OPTIONAL_SEQUENTIAL_SPECIALISTS

        listOf(plan, build, verify).forEach { prompt ->
            assertTrue(prompt.isNotBlank())
            assertTrue(prompt.trim().split(Regex("\\s+")).size <= 100)
            assertTrue(specialistNames.none { prompt.contains(it) })
        }

        assertTrue(plan.contains("at most 2 search calls total"))
        assertTrue(plan.contains("4 fetches"))
        assertTrue(plan.contains("exact formulas"))
        assertTrue(plan.contains("runtime dependencies"))
        assertTrue(plan.contains("bounded resource behavior"))
        assertTrue(plan.contains("concrete checks"))
        assertTrue(plan.contains("propose_plan"))
        assertTrue(plan.contains("wait for approval"))
        assertFalse(plan.contains("write_file"))
        assertFalse(plan.contains("run_project"))

        assertTrue(build.contains("explicit plan approval"))
        assertTrue(build.contains("greenfield"))
        assertTrue(build.contains("entry files first with write_file"))
        assertTrue(build.contains("existing work"))
        assertTrue(build.contains("read only files relevant"))
        assertTrue(build.contains("increment runnable"))
        assertTrue(build.contains("focused checks"))

        assertTrue(verify.contains("focused checks"))
        assertTrue(verify.contains("tool_help"))
        assertTrue(verify.contains("report_progress with phase=build"))
        assertTrue(verify.contains("finish_task"))
        assertTrue(verify.contains("Do not write, edit, patch, install dependencies"))
    }

    @Test
    fun `greenfield or explicit no scout directive suppresses only discovery tools`() {
        assertTrue(
            AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                greenfield = true,
                initialGoal = "Build a new app.",
                corrections = emptyList()
            )
        )
        assertTrue(
            AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                greenfield = false,
                initialGoal = "Extend the existing app.",
                corrections = listOf("There is no need to scout the codebase; use named files only.")
            )
        )
        assertTrue(
            AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                greenfield = false,
                initialGoal = "No need to inspect the repository before this focused change.",
                corrections = emptyList()
            )
        )
        assertFalse(
            AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                greenfield = true,
                initialGoal = "Now inspect the existing codebase for the requested change.",
                corrections = emptyList()
            )
        )
        assertFalse(
            AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                greenfield = true,
                initialGoal = "Greenfield project; no need to scout the codebase.",
                corrections = listOf("Now inspect the existing codebase for the approved change.")
            )
        )
        assertFalse(
            AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                greenfield = false,
                initialGoal = "There is no need to scout the repository.",
                corrections = listOf(
                    "You may scout the codebase now to locate the requested integration point."
                )
            )
        )
        assertFalse(
            AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                greenfield = false,
                initialGoal = "Existing project.",
                corrections = listOf(
                    "Now inspect the existing codebase. No need to scout the codebase. " +
                        "You may scout the codebase."
                )
            )
        )

        assertTrue(AgentHarnessPolicy.isCodebaseDiscoveryTool(" LIST_DIRECTORY "))
        assertTrue(AgentHarnessPolicy.isCodebaseDiscoveryTool("search_code"))
        assertFalse(AgentHarnessPolicy.isCodebaseDiscoveryTool("read_file"))
        assertFalse(AgentHarnessPolicy.isCodebaseDiscoveryTool("project_state_read"))
    }

    @Test
    fun `existing project without explicit no scout directive keeps discovery available`() {
        assertFalse(
            AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                greenfield = false,
                initialGoal = "Update the existing repository and inspect its current structure.",
                corrections = listOf("Read the relevant files before editing them.")
            )
        )
        assertFalse(
            AgentHarnessPolicy.shouldSuppressCodebaseDiscovery(
                greenfield = false,
                initialGoal = "Update the existing repository; do not skip scouting checks.",
                corrections = emptyList()
            )
        )
    }

    @Test
    fun `optimized local sandbox guidance prioritizes browser web architecture`() {
        val guidance = AgentHarnessPolicy.OPTIMIZED_LOCAL_SANDBOX_GUIDANCE

        assertTrue(guidance.startsWith("LOCAL_SANDBOX WEB FIRST:"))
        assertTrue(guidance.contains("runtime=web"))
        assertTrue(guidance.indexOf("runtime=web") < guidance.indexOf("runtime=python"))
        assertTrue(guidance.contains("ui=web"))
        assertTrue(guidance.contains("static HTML/CSS/JS"))
        assertTrue(guidance.contains("Web Workers"))
        assertTrue(guidance.contains("IndexedDB"))
        assertTrue(guidance.contains("bounded export chunks"))
        assertTrue(guidance.contains("finite non-UI checks"))
        assertTrue(guidance.contains("runtime=python"))
        assertTrue(guidance.contains("ui=console"))
        assertTrue(guidance.contains("Node/Express/Flask/Django"))
        assertFalse(guidance.contains("Prefer Python standard-library and static web assets"))
    }

    @Test
    fun `optimized policy retains every optional specialist as a sequential capability`() {
        assertEquals(
            setOf(
                "CODEBASE_SCOUT",
                "RESEARCHER",
                "PLANNER",
                "CODER",
                "REVIEWER",
                "EXECUTOR",
                "SUMMARIZER",
                "VISUAL_TESTER"
            ),
            AgentHarnessPolicy.OPTIONAL_SEQUENTIAL_SPECIALISTS.toSet()
        )
    }

    @Test
    fun `optimized policy preserves explicit thinking choice`() {
        assertEquals(
            false,
            AgentHarnessPolicy.forProfile(
                AgentHarnessProfile.OPTIMIZED,
                explicitThinkingEnabled = false
            ).thinkingOverride
        )
        assertNull(
            AgentHarnessPolicy.forProfile(AgentHarnessProfile.OPTIMIZED)
                .thinkingOverride
        )
    }

    @Test
    fun `root phase tool sets expose direct plan build and verify capabilities`() {
        val plan = AgentHarnessPolicy.rootToolsForPhase(AgentHarnessPhase.PLAN)
        val build = AgentHarnessPolicy.rootToolsForPhase(AgentHarnessPhase.BUILD)
        val verify = AgentHarnessPolicy.rootToolsForPhase(AgentHarnessPhase.VERIFY)

        assertTrue(
            plan.containsAll(
                setOf(
                    "project_state_read",
                    "read_file",
                    "web_search",
                    "fetch_url",
                    "call_agent",
                    "propose_plan"
                )
            )
        )
        assertTrue(
            build.containsAll(
                setOf(
                    "project_state_read",
                    "write_file",
                    "apply_patch",
                    "run_command",
                    "run_project",
                    "check_project_run",
                    "stop_project_run",
                    "force_stop_project_run",
                    "view_image",
                    "observe_preview",
                    "interact_preview"
                )
            )
        )
        assertTrue(
            verify.containsAll(
                setOf(
                    "read_file",
                    "run_project",
                    "check_project_run",
                    "observe_preview",
                    "interact_preview",
                    "view_image"
                )
            )
        )
        assertFalse(plan.contains("write_file"))
        assertFalse(verify.contains("write_file"))
        assertFalse(verify.contains("install_python_dependency"))
    }

    @Test
    fun `legacy policy leaves optimized tuning unset`() {
        val policy = AgentHarnessPolicy.forProfile(AgentHarnessProfile.LEGACY)

        assertNull(policy.defaultContextTokens)
        assertNull(policy.maxOutputTokens(AgentHarnessStage.CONTROL))
        assertNull(policy.maxOutputTokens(AgentHarnessStage.BUILD))
        assertNull(policy.maxOutputTokens(AgentHarnessStage.SUMMARY))
        assertNull(policy.optimizedSystemPrompt)
        assertTrue(policy.isLegacy)
    }

    private fun agentTool(name: String): AgentTool = AgentTool(
        name = name,
        description = "$name fixture tool",
        parameters = emptyMap()
    )
}
