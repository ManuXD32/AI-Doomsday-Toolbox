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
    fun `orchestrator call agent schema requires a name and documents duplicate suffixing`() {
        val callAgent = AgentService.getAgentTools(settingsRepo = mockAgentSettings(
            webSearchEnabled = false,
            kiwixEnabled = false,
            imageGenerationEnabled = false,
            backgroundRemovalEnabled = false,
            visionEnabled = false
        )).single { it.name == "call_agent" }

        assertEquals(listOf("agent", "name", "task"), callAgent.requiredParams)
        assertTrue(callAgent.parameters.containsKey("context"))
        assertTrue(callAgent.description.contains("Orchestrator-only"))
        assertTrue(callAgent.description.contains("automatically adds 2, 3"))
        assertTrue(callAgent.parameters.getValue("name").contains("maximum 40"))
        assertTrue(callAgent.description.contains("exactly ONE atomic todo-sized task"))
        assertTrue(callAgent.parameters.getValue("task").contains("never the whole plan"))
    }

    @Test
    fun `LiteRT agent history preserves tool call and tool result roles`() {
        val call = OllamaService.ToolCall(
            id = "call-1",
            name = "read_file",
            arguments = mapOf("path" to "src/main.kt")
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

        val mappedAssistant = AgentService.chatMessageToLiteRtConversationMessage(assistant)
        val mappedResult = AgentService.chatMessageToLiteRtConversationMessage(result, call.name)

        assertEquals("assistant", mappedAssistant.role)
        assertEquals("read_file", mappedAssistant.toolCalls.single().name)
        assertEquals("src/main.kt", mappedAssistant.toolCalls.single().arguments["path"])
        assertEquals("tool", mappedResult.role)
        assertEquals("read_file", mappedResult.toolName)
    }

    @Test
    fun `agent tools hide web and image tools when settings are disabled`() {
        val repo = mockAgentSettings(
            webSearchEnabled = false,
            kiwixEnabled = false,
            imageGenerationEnabled = false,
            backgroundRemovalEnabled = false,
            visionEnabled = false
        )

        val names = AgentService.getAgentTools(settingsRepo = repo).map { it.name }

        assertFalse("web_search should not be advertised when Agent Web Search is disabled", "web_search" in names)
        assertFalse("fetch_url should not be advertised when Agent Web Search is disabled", "fetch_url" in names)
        assertFalse("kiwix_search should not be advertised when Agent Kiwix is disabled", "kiwix_search" in names)
        assertFalse("generate_image should not be advertised when Agent image generation is disabled", "generate_image" in names)
        assertFalse("remove_image_background should not be advertised when Agent background removal is disabled", "remove_image_background" in names)
        assertFalse("view_image should not be advertised when current-agent vision is disabled", "view_image" in names)
        assertTrue("core file tools should remain available", "read_file" in names)
    }

    @Test
    fun `agent tools expose optional tools only when matching settings are enabled`() {
        val repo = mockAgentSettings(
            webSearchEnabled = true,
            kiwixEnabled = true,
            imageGenerationEnabled = true,
            backgroundRemovalEnabled = true,
            visionEnabled = true
        )

        val tools = AgentService.getAgentTools(settingsRepo = repo)
        val names = tools.map { it.name }

        assertTrue("web_search should be advertised when Agent Web Search is enabled", "web_search" in names)
        assertTrue("fetch_url should be advertised when Agent Web Search is enabled", "fetch_url" in names)
        assertTrue("kiwix_search should be advertised when Agent Kiwix is enabled", "kiwix_search" in names)
        assertTrue("generate_image should be advertised when Agent image generation is enabled", "generate_image" in names)
        assertTrue("remove_image_background should be advertised when Agent background removal is enabled", "remove_image_background" in names)
        assertTrue("view_image should be advertised when current-agent vision is enabled", "view_image" in names)
        val imageTool = tools.single { it.name == "generate_image" }
        assertTrue(imageTool.description.contains("configured image engine"))
        assertFalse(imageTool.description.contains("ONNX image model"))
    }

    private fun mockAgentSettings(
        webSearchEnabled: Boolean,
        kiwixEnabled: Boolean,
        imageGenerationEnabled: Boolean,
        backgroundRemovalEnabled: Boolean,
        visionEnabled: Boolean
    ): SettingsRepository {
        val repo = mockk<SettingsRepository>()
        every { repo.agentWebSearchEnabled } returns MutableStateFlow(webSearchEnabled)
        every { repo.agentKiwixEnabled } returns MutableStateFlow(kiwixEnabled)
        every { repo.agentImageGenerationToolEnabled } returns MutableStateFlow(imageGenerationEnabled)
        every { repo.agentBackgroundRemovalToolEnabled } returns MutableStateFlow(backgroundRemovalEnabled)
        every { repo.agentVisualTestingEnabled } returns MutableStateFlow(false)
        every { repo.getAgentVisionEnabledForRole(any()) } returns visionEnabled
        return repo
    }
}
