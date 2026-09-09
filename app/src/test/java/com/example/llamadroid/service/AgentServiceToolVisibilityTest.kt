package com.example.llamadroid.service

import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.runtime.AgentRuntimeDispatchSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun `orchestrator receives only bounded control plane tools`() {
        val names = AgentService.getAgentTools(
            role = AgentService.Companion.AgentRole.ORCHESTRATOR,
            settingsRepo = mockAgentSettings(
                webSearchEnabled = true,
                kiwixEnabled = true,
                imageGenerationEnabled = true,
                backgroundRemovalEnabled = true,
                visionEnabled = true
            )
        ).map { it.name }.toSet()

        assertTrue("project_state_read" in names)
        assertTrue("agent_report_read" in names)
        assertTrue("todo_transition" in names)
        assertTrue("call_agent" in names)
        assertTrue("propose_plan" in names)
        assertFalse("read_file" in names)
        assertFalse("search_code" in names)
        assertFalse("write_file" in names)
        assertFalse("run_command" in names)
        assertFalse("web_search" in names)
        assertFalse("fetch_url" in names)
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
    fun `saved no scout policy removes discovery in Plan and restores it in Build`() {
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
            val planTools = AgentService.getAgentTools(
                role = AgentService.Companion.AgentRole.ORCHESTRATOR, settingsRepo = settings
            )
            assertFalse(planTools.any { it.name in setOf("list_directory", "search_code") })
            assertTrue(planTools.any { it.name == "read_file" })
            assertTrue(planTools.any { it.name == "propose_plan" })
            assertFalse(planTools.single { it.name == "call_agent" }.parameters.getValue("agent").contains("CODEBASE_SCOUT"))
            AgentService.setCurrentPlanningModeEnabled(false)
            val buildTools = AgentService.getAgentTools(
                role = AgentService.Companion.AgentRole.ORCHESTRATOR, settingsRepo = settings
            )
            assertTrue(buildTools.any { it.name == "list_directory" })
            assertTrue(buildTools.any { it.name == "write_file" })
            AgentService.setCurrentPlanningModeEnabled(true)
            AgentService.updateCodebaseDiscoveryPolicy(999L, false)
            assertTrue(AgentService.getAgentTools(
                role = AgentService.Companion.AgentRole.ORCHESTRATOR, settingsRepo = settings
            ).any { it.name == "list_directory" })
        } finally {
            AgentService.setActiveConversationId(oldConversation)
            AgentService.setPreferredConversationId(oldPreferred)
            AgentService.setExecutionProfile(oldProfile)
            AgentService.setCurrentWorkspaceBackend(oldBackend)
            AgentService.setCurrentPlanningModeEnabled(oldPlanMode)
        }
    }

    @Test
    fun `optimized local root can build and finish with compact schemas`() {
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
            val names = tools.map { it.name }.toSet()
            assertTrue("write_file" in names)
            assertTrue("run_project" in names)
            assertTrue("check_project_run" in names)
            assertTrue("finish_task" in names)
            assertFalse("run_command" in names)
            assertFalse("propose_plan" in names)
            val compact = compactAgentToolSchemas(tools)
            assertEquals(names, compact.map { it.name }.toSet())
            val rawTokens = estimateRawAgentToolSchemaTokens(tools)
            val compactTokens = estimateRawAgentToolSchemaTokens(compact)
            println("Optimized local Build schemas: tools=${tools.size} raw=$rawTokens compact=$compactTokens")
            assertTrue(compactTokens < rawTokens)
            val core = compactAgentToolSchemas(AgentToolSchemaPolicy.selectOptimizedToolPalette(tools, AgentHarnessPhase.BUILD))
            val coreTokens = estimateRawAgentToolSchemaTokens(core)
            println("Optimized local Build core: tools=${core.size} tokens=$coreTokens")
            assertTrue("Core schemas must leave space for protected state at 8K", coreTokens < 1_600)
            assertTrue(core.map { it.name }.containsAll(listOf("write_file", "run_project", "check_project_run", "finish_task", "tool_help")))
            val expanded = AgentToolSchemaPolicy.selectOptimizedToolPalette(tools, AgentHarnessPhase.BUILD, "project_state_read")
            assertTrue(expanded.any { it.name == "project_state_read" })
            assertTrue(tools.single { it.name == "finish_task" }.parameters.keys.containsAll(listOf("summary", "artifacts", "validation")))
        } finally {
            AgentService.setExecutionProfile(oldProfile)
            AgentService.setCurrentWorkspaceBackend(oldBackend)
            AgentService.setCurrentPlanningModeEnabled(oldPlanMode)
        }
    }

    @Test
    fun `text root preview tools respect preview toggle and retain read only tester contract`() {
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
            assertFalse(AgentService.getAgentTools(
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
        every { repo.resolveAgentSettingsForDispatch(any(), any(), any(), any()) } returns
            AgentRuntimeDispatchSettings(
                backend = "ollama",
                model = "test-model",
                contextSize = 4096,
                maxOutputTokens = 1024,
                thinkingEnabled = false,
                visionEnabled = visionEnabled
            )
        return repo
    }
}
