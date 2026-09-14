package com.example.llamadroid.service

import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.runtime.AgentRuntimeDispatchSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentServiceToolVisibilityTest {
    @Test
    fun `orchestrator call agent schema supports planning specialists and TODO ownership`() {
        val callAgent = AgentService.getAgentTools(
            role = AgentService.Companion.AgentRole.ORCHESTRATOR,
            settingsRepo = mockAgentSettings(
                webSearchEnabled = true,
                kiwixEnabled = true,
                imageGenerationEnabled = false,
                backgroundRemovalEnabled = false,
                visionEnabled = false
            )
        ).single { it.name == "call_agent" }

        assertEquals(listOf("agent", "name", "task"), callAgent.requiredParams)
        assertTrue(callAgent.parameters.containsKey("todo_id"))
        assertTrue(callAgent.description.contains("CODEBASE_SCOUT"))
        assertTrue(callAgent.description.contains("RESEARCHER"))
        assertTrue(callAgent.description.contains("PLANNER"))
        assertTrue(callAgent.description.contains("structured reports"))
    }

    @Test
    fun `direct request palette keeps core tools and removes archived control plane tools`() {
        val catalog = AgentService.getAgentTools(
            role = AgentService.Companion.AgentRole.ORCHESTRATOR,
            settingsRepo = mockAgentSettings(
                webSearchEnabled = true,
                kiwixEnabled = true,
                imageGenerationEnabled = true,
                backgroundRemovalEnabled = true,
                visionEnabled = true
            )
        )
        val palette = AgentToolSchemaPolicy.selectDirectToolPalette(
            tools = catalog,
            backend = AgentDirectBackend.LOCAL
        )
        val names = palette.map { it.name }.toSet()

        assertEquals(
            AgentHarnessPolicy.DIRECT_CORE_TOOL_NAMES,
            names
        )
        assertFalse("project_state_read" in names)
        assertFalse("agent_report_read" in names)
        assertFalse("todo_transition" in names)
        assertFalse("call_agent" in names)
        assertFalse("propose_plan" in names)
        assertFalse("run_command" in names)
        assertFalse("web_search" in names)
        val writeSchema = JSONObject(requireNotNull(palette.single { it.name == "write_file" }.schemaJson))
        assertEquals(
            AgentHarnessPolicy.DIRECT_WRITE_FILE_MAX_BYTES,
            writeSchema.getJSONObject("properties").getJSONObject("content").getInt("maxLength")
        )
        assertFalse(writeSchema.optBoolean("additionalProperties", true))
        val editSchema = JSONObject(requireNotNull(palette.single { it.name == "edit_file" }.schemaJson))
        assertEquals(
            AgentHarnessPolicy.DIRECT_WRITE_FILE_MAX_BYTES,
            editSchema.getJSONObject("properties").getJSONObject("new_text").getInt("maxLength")
        )
    }

    @Test
    fun `strict direct recovery advertises one tool with a smaller mutation bound`() {
        val catalog = AgentService.getAgentTools(
            role = AgentService.Companion.AgentRole.ORCHESTRATOR,
            settingsRepo = mockAgentSettings(
                webSearchEnabled = false,
                kiwixEnabled = false,
                imageGenerationEnabled = false,
                backgroundRemovalEnabled = false,
                visionEnabled = false
            )
        )

        val strict = AgentToolSchemaPolicy.selectDirectStrictRecoveryTool(
            tools = catalog,
            backend = AgentDirectBackend.LOCAL,
            toolName = "write_file"
        )

        assertEquals(listOf("write_file"), strict.map { it.name })
        val schema = JSONObject(requireNotNull(strict.single().schemaJson))
        assertEquals(
            AgentToolSchemaPolicy.DIRECT_STRICT_RECOVERY_MUTATION_MAX_CHARS,
            schema.getJSONObject("properties").getJSONObject("content").getInt("maxLength")
        )
        assertTrue(strict.single().description.contains("below 2 KiB"))
    }

    @Test
    fun `codebase scout is read only and project scoped`() {
        val names = AgentService.getAgentTools(
            role = AgentService.Companion.AgentRole.CODEBASE_SCOUT,
            settingsRepo = mockAgentSettings(
                webSearchEnabled = true,
                kiwixEnabled = true,
                imageGenerationEnabled = true,
                backgroundRemovalEnabled = true,
                visionEnabled = false
            )
        ).map { it.name }.toSet()

        assertTrue("read_file" in names)
        assertTrue("search_code" in names)
        assertTrue("list_directory" in names)
        assertTrue("finish_task" in names)
        assertFalse("write_file" in names)
        assertFalse("apply_patch" in names)
        assertFalse("run_command" in names)
        assertFalse("web_search" in names)
    }

    @Test
    fun `researcher receives research tools but no project mutation`() {
        val names = AgentService.getAgentTools(
            role = AgentService.Companion.AgentRole.RESEARCHER,
            settingsRepo = mockAgentSettings(
                webSearchEnabled = true,
                kiwixEnabled = true,
                imageGenerationEnabled = false,
                backgroundRemovalEnabled = false,
                visionEnabled = false
            )
        ).map { it.name }.toSet()

        assertTrue("web_search" in names)
        assertTrue("fetch_url" in names)
        assertTrue("kiwix_search" in names)
        assertTrue("kb_search" in names)
        assertTrue("finish_task" in names)
        assertFalse("read_file" in names)
        assertFalse("write_file" in names)
        assertFalse("run_command" in names)
    }

    @Test
    fun `planner sees state and reports without source or mutation tools`() {
        val names = AgentService.getAgentTools(
            role = AgentService.Companion.AgentRole.PLANNER,
            settingsRepo = mockAgentSettings(
                webSearchEnabled = true,
                kiwixEnabled = true,
                imageGenerationEnabled = false,
                backgroundRemovalEnabled = false,
                visionEnabled = false
            )
        ).map { it.name }.toSet()

        assertTrue("project_state_read" in names)
        assertTrue("project_order_read" in names)
        assertTrue("plan_read" in names)
        assertTrue("agent_report_read" in names)
        assertTrue("finish_task" in names)
        assertFalse("search_code" in names)
        assertFalse("web_search" in names)
        assertFalse("write_file" in names)
    }

    @Test
    fun `coder has mutation tools but not command or research tools`() {
        val names = AgentService.getAgentTools(
            role = AgentService.Companion.AgentRole.CODER,
            settingsRepo = mockAgentSettings(
                webSearchEnabled = true,
                kiwixEnabled = true,
                imageGenerationEnabled = true,
                backgroundRemovalEnabled = true,
                visionEnabled = true
            )
        ).map { it.name }.toSet()

        assertTrue("read_file" in names)
        assertTrue("write_file" in names)
        assertTrue("apply_patch" in names)
        assertTrue("generate_image" in names)
        assertTrue("remove_image_background" in names)
        assertTrue("view_image" in names)
        assertTrue("finish_task" in names)
        assertFalse("run_command" in names)
        assertFalse("web_search" in names)
        assertFalse("call_agent" in names)
    }

    @Test
    fun `researcher remains advertised when online research providers are disabled`() {
        val callAgent = AgentService.getAgentTools(
            role = AgentService.Companion.AgentRole.ORCHESTRATOR,
            settingsRepo = mockAgentSettings(
                webSearchEnabled = false,
                kiwixEnabled = false,
                imageGenerationEnabled = false,
                backgroundRemovalEnabled = false,
                visionEnabled = false
            )
        ).single { it.name == "call_agent" }

        assertTrue(callAgent.parameters.getValue("agent").contains("RESEARCHER"))
    }

    @Test
    fun `LiteRT agent history preserves tool call and tool result roles`() {
        val call = OllamaService.ToolCall(
            name = "read_file",
            arguments = mapOf("path" to "src/main.kt"),
            id = "call-1"
        )
        val assistant = AgentService.Companion.ChatMessage(
            role = "assistant",
            content = "",
            pendingToolCall = call,
            toolCallId = call.id,
            toolName = call.name
        )
        val result = AgentService.Companion.ChatMessage(
            role = "tool",
            content = "file contents",
            toolCallId = call.id,
            toolName = call.name
        )

        val mappedAssistant =
            AgentService.chatMessageToLiteRtConversationMessage(assistant)
        val mappedResult = AgentService.chatMessageToLiteRtConversationMessage(
            result,
            call.name
        )

        assertEquals("assistant", mappedAssistant.role)
        assertEquals("read_file", mappedAssistant.toolCalls.single().name)
        assertEquals(
            "src/main.kt",
            mappedAssistant.toolCalls.single().arguments["path"]
        )
        assertEquals("tool", mappedResult.role)
        assertEquals("read_file", mappedResult.toolName)
    }

    @Test
    fun `direct core schema stays stable when phase and old scout preference change`() {
        val oldProfile = AgentService.executionProfile.value
        val oldBackend = AgentService.currentWorkspaceBackend.value
        val oldPlanMode = AgentService.currentPlanningModeEnabled.value
        val oldConversation = AgentService.activeConversationId.value
        val oldPreferred = AgentService.preferredConversationId.value
        try {
            AgentService.setActiveConversationId(999L)
            AgentService.setExecutionProfile(AgentHarnessPolicy.OPTIMIZED)
            AgentService.setCurrentWorkspaceBackend(AgentWorkspaceBackendType.LOCAL_SANDBOX)
            AgentService.setCurrentPlanningModeEnabled(true)
            AgentService.updateCodebaseDiscoveryPolicy(999L, true)
            val settings = mockAgentSettings(true, true, false, false, false)
            val planTools = AgentToolSchemaPolicy.selectDirectToolPalette(AgentService.getAgentTools(
                role = AgentService.Companion.AgentRole.ORCHESTRATOR, settingsRepo = settings
            ), AgentDirectBackend.LOCAL)
            assertTrue(planTools.any { it.name in setOf("list_directory", "search_code") })
            assertTrue(planTools.any { it.name == "read_file" })
            assertFalse(planTools.any { it.name == "propose_plan" })
            assertFalse(planTools.any { it.name == "call_agent" })
            AgentService.setCurrentPlanningModeEnabled(false)
            val buildTools = AgentToolSchemaPolicy.selectDirectToolPalette(AgentService.getAgentTools(
                role = AgentService.Companion.AgentRole.ORCHESTRATOR, settingsRepo = settings
            ), AgentDirectBackend.LOCAL)
            assertEquals(planTools.map { it.name }, buildTools.map { it.name })
            AgentService.setCurrentPlanningModeEnabled(true)
            AgentService.updateCodebaseDiscoveryPolicy(999L, false)
            val restored = AgentToolSchemaPolicy.selectDirectToolPalette(AgentService.getAgentTools(
                role = AgentService.Companion.AgentRole.ORCHESTRATOR, settingsRepo = settings
            ), AgentDirectBackend.LOCAL)
            assertEquals(planTools.map { it.name }, restored.map { it.name })
        } finally {
            AgentService.setActiveConversationId(oldConversation)
            AgentService.setPreferredConversationId(oldPreferred)
            AgentService.setExecutionProfile(oldProfile)
            AgentService.setCurrentWorkspaceBackend(oldBackend)
            AgentService.setCurrentPlanningModeEnabled(oldPlanMode)
        }
    }

    @Test
    fun `direct local root stays below the stable prefix budget`() {
        val oldProfile = AgentService.executionProfile.value
        val oldBackend = AgentService.currentWorkspaceBackend.value
        val oldPlanMode = AgentService.currentPlanningModeEnabled.value
        try {
            AgentService.setExecutionProfile(AgentHarnessPolicy.OPTIMIZED)
            AgentService.setCurrentWorkspaceBackend(AgentWorkspaceBackendType.LOCAL_SANDBOX)
            AgentService.setCurrentPlanningModeEnabled(false)
            val tools = AgentService.getAgentTools(
                role = AgentService.Companion.AgentRole.ORCHESTRATOR,
                settingsRepo = mockAgentSettings(true, true, false, false, false)
            )
            val core = compactAgentToolSchemas(
                AgentToolSchemaPolicy.selectDirectToolPalette(tools, AgentDirectBackend.LOCAL)
            )
            val names = core.map { it.name }.toSet()
            val coreTokens = estimateRawAgentToolSchemaTokens(core)
            val promptTokens = estimateRawPromptTextTokens(AgentHarnessPolicy.directSystemPrompt())
            assertEquals(
                AgentHarnessPolicy.DIRECT_CORE_TOOL_NAMES +
                    AgentToolSchemaPolicy.DIRECT_LOCAL_TOOL_NAMES,
                names
            )
            assertTrue(promptTokens + coreTokens < AgentHarnessPolicy.DIRECT_STABLE_PREFIX_MAX_TOKENS)
            assertFalse("check_project_run" in names)
            assertFalse("propose_plan" in names)
            val expanded = AgentToolSchemaPolicy.selectDirectToolPalette(
                tools,
                AgentDirectBackend.LOCAL,
                activatedTool = "web_search"
            )
            assertTrue(expanded.any { it.name == "web_search" })
            assertTrue(tools.single { it.name == "finish_task" }.parameters.keys.containsAll(listOf("summary", "artifacts", "validation")))
        } finally {
            AgentService.setExecutionProfile(oldProfile)
            AgentService.setCurrentWorkspaceBackend(oldBackend)
            AgentService.setCurrentPlanningModeEnabled(oldPlanMode)
        }
    }

    @Test
    fun `direct local preview schema stays stable while tester records remain read only`() {
        val oldProfile = AgentService.executionProfile.value
        val oldBackend = AgentService.currentWorkspaceBackend.value
        val oldPlanMode = AgentService.currentPlanningModeEnabled.value
        mockkObject(AgentPreviewBridge)
        try {
            every { AgentPreviewBridge.hasActivePreview(any()) } returns true
            AgentService.setExecutionProfile(AgentHarnessPolicy.OPTIMIZED)
            AgentService.setCurrentWorkspaceBackend(AgentWorkspaceBackendType.LOCAL_SANDBOX)
            AgentService.setCurrentPlanningModeEnabled(false)
            val settings = mockAgentSettings(true, false, false, false, false)
            val previewEnabled = MutableStateFlow(true)
            every { settings.agentVisualTestingEnabled } returns previewEnabled
            val rootTools = AgentService.getAgentTools(
                role = AgentService.Companion.AgentRole.ORCHESTRATOR,
                settingsRepo = settings
            )
            assertTrue(rootTools.any { it.name == "observe_preview" })
            assertTrue(rootTools.single { it.name == "interact_preview" }
                .parameters.getValue("action").contains("fill"))
            val testerTools = AgentService.getAgentTools(
                role = AgentService.Companion.AgentRole.VISUAL_TESTER,
                settingsRepo = settings
            ).map { it.name }.toSet()
            assertEquals(setOf("observe_preview", "interact_preview", "tool_help", "finish_task"), testerTools)
            previewEnabled.value = false
            assertTrue(AgentService.getAgentTools(
                role = AgentService.Companion.AgentRole.ORCHESTRATOR,
                settingsRepo = settings
            ).any { it.name in setOf("observe_preview", "interact_preview") })
        } finally {
            unmockkObject(AgentPreviewBridge)
            AgentService.setExecutionProfile(oldProfile)
            AgentService.setCurrentWorkspaceBackend(oldBackend)
            AgentService.setCurrentPlanningModeEnabled(oldPlanMode)
        }
    }

    private fun mockAgentSettings(
        webSearchEnabled: Boolean,
        kiwixEnabled: Boolean,
        imageGenerationEnabled: Boolean,
        backgroundRemovalEnabled: Boolean,
        visionEnabled: Boolean
    ): SettingsRepository {
        val repo = mockk<SettingsRepository>()
        every { repo.agentWebSearchEnabled } returns
            MutableStateFlow(webSearchEnabled)
        every { repo.agentKiwixEnabled } returns
            MutableStateFlow(kiwixEnabled)
        every { repo.agentImageGenerationToolEnabled } returns
            MutableStateFlow(imageGenerationEnabled)
        every { repo.agentBackgroundRemovalToolEnabled } returns
            MutableStateFlow(backgroundRemovalEnabled)
        every { repo.agentVisualTestingEnabled } returns MutableStateFlow(false)
        val dispatch = AgentRuntimeDispatchSettings(
                backend = "ollama",
                model = "test-model",
                contextSize = 4096,
                maxOutputTokens = 1024,
                thinkingEnabled = false,
                visionEnabled = visionEnabled
            )
        every { repo.resolveAgentSettingsForDispatch(any(), any(), any(), any()) } returns dispatch
        return repo
    }
}
