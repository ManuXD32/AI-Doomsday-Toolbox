package com.example.llamadroid.service

import com.example.llamadroid.data.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentServiceToolVisibilityTest {
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
        every { repo.getAgentVisionEnabledForRole(any()) } returns visionEnabled
        return repo
    }
}
