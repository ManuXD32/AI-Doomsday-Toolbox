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
        assertTrue(ToolCatalog.matchesRoute("${Screen.ImageGen.route}?startMode={startMode}&tab={tab}"))
        assertTrue(ToolCatalog.matchesRoute(Screen.LlamaChat.route))
        assertTrue(ToolCatalog.matchesRoute(Screen.DatasetProject.route))
    }

    @Test
    fun `soft studio catalog keeps task and infrastructure entry points discoverable`() {
        val byId = ToolCatalog.tools.associateBy { it.id }

        assertEquals(ToolCategory.CREATE, byId.getValue("onnx_image_generation").category)
        assertEquals(ToolCategory.VOICE, byId.getValue("audio_workspace").category)
        assertEquals(ToolCategory.DOCUMENTS, byId.getValue("pdf_tools").category)
        assertEquals(ToolCategory.SHARE_NETWORK, byId.getValue("servers_hub").category)
        assertEquals(ToolCategory.SHARE_NETWORK, byId.getValue("distributed_llm").category)
        assertEquals(ToolCategory.SHARE_NETWORK, byId.getValue("distributed_media").category)
        assertEquals(ToolCategory.ADVANCED, byId.getValue("quadtrix").category)
        assertTrue("ONNX search keyword was dropped", byId.getValue("onnx_tts").keywords.contains("onnx"))
        assertTrue("File sharing is not searchable", byId.getValue("file_server").keywords.contains("share"))
    }

    @Test
    fun `tools moved to other tabs are hidden from hub but keep route ownership`() {
        val byId = ToolCatalog.tools.associateBy { it.id }
        listOf("chat", "agent", "native_llama", "organizer", "knowledge", "model_library", "onnx_tts").forEach {
            assertTrue("$it should be hidden from the hub", !byId.getValue(it).showInHub)
        }
        assertTrue(ToolCatalog.matchesRoute(Screen.Agent.route))
        assertTrue(ToolCatalog.matchesRoute(Screen.OnnxTts.route))
        assertTrue(byId.getValue("file_server").showInHub)
    }

    @Test
    fun `visible tiles use distinct icons`() {
        val visible = ToolCatalog.tools.filter { it.showInHub }
        val duplicates = visible.groupBy { it.icon }.filterValues { it.size > 1 }
        assertTrue("Duplicate icons: ${duplicates.values.map { l -> l.map { it.id } }}", duplicates.isEmpty())
    }
}
