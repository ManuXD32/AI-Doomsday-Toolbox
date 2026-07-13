package com.example.llamadroid.ui.ai

import com.example.llamadroid.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogTest {
    @Test
    fun `tool ids are unique`() {
        val ids = ToolCatalog.tools.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every category has tools`() {
        ToolCategory.entries.forEach { category ->
            assertTrue(
                "Missing tools for $category",
                ToolCatalog.tools.any { it.category == category }
            )
        }
    }

    @Test
    fun `tools have stable route and string resources`() {
        ToolCatalog.tools.forEach { tool ->
            assertTrue("Blank route for ${tool.id}", tool.route.isNotBlank())
            assertTrue("Missing route pattern for ${tool.id}", tool.routePatterns.isNotEmpty())
            assertNotEquals("Missing title resource for ${tool.id}", 0, tool.titleRes)
            assertNotEquals("Missing description resource for ${tool.id}", 0, tool.descriptionRes)
            assertNotEquals("Missing category resource for ${tool.id}", 0, tool.category.titleRes)
        }
    }

    @Test
    fun `tool settings actions cover detached settings pages`() {
        val settingsByTool = ToolCatalog.tools.associate { it.id to it.settingsAction }

        assertEquals(
            ToolSettingsAction.Sheet(ToolSettingsSheet.IMAGE_GENERATION),
            settingsByTool.getValue("image_generation")
        )
        assertEquals(
            ToolSettingsAction.Sheet(ToolSettingsSheet.WHISPER),
            settingsByTool.getValue("transcription")
        )
        assertEquals(
            ToolSettingsAction.Sheet(ToolSettingsSheet.VIDEO_UPSCALER),
            settingsByTool.getValue("video_upscaler")
        )
        assertEquals(
            ToolSettingsAction.Sheet(ToolSettingsSheet.PDF_SUMMARY),
            settingsByTool.getValue("pdf_tools")
        )
    }

    @Test
    fun `route matcher includes tool subroutes`() {
        assertTrue(ToolCatalog.matchesRoute(Screen.AIHub.route).not())
        assertTrue(ToolCatalog.matchesRoute("${Screen.ImageGen.route}?startMode={startMode}"))
        assertTrue(ToolCatalog.matchesRoute(Screen.LlamaChat.route))
        assertTrue(ToolCatalog.matchesRoute(Screen.DatasetProject.route))
    }
}
