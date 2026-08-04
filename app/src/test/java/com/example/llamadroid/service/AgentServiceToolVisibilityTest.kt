package com.example.llamadroid.service

import com.example.llamadroid.data.SettingsRepository
import io.mockk.every
import io.mockk.mockk
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
        every { repo.getAgentVisionEnabledForRole(any()) } returns visionEnabled
        return repo
    }
}
