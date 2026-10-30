package com.example.llamadroid.ui.ai

import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.servers.SERVERS_HUB_ROUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogTest {
    @Test
    fun `tool ids are unique`() {
        val ids = ToolCatalog.tools.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every category has grid tools and assistants come first`() {
        ToolCategory.entries.forEach { category ->
            assertTrue(
                "Missing grid tools for $category",
                ToolCatalog.gridTools.any { it.category == category }
            )
        }
        assertEquals(ToolCategory.ASSISTANTS, ToolCategory.entries.first())
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
    fun `visible entries have english and spanish keywords`() {
        ToolCatalog.tools.filter { it.visibility != ToolVisibility.HIDDEN }.forEach { tool ->
            assertTrue("No keywords for ${tool.id}", tool.keywords.isNotEmpty())
            assertTrue("No Spanish keywords for ${tool.id}", tool.keywordsEs.isNotEmpty())
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
    fun `toolForRoute resolves every catalog route and concrete routes with arguments`() {
        ToolCatalog.tools.forEach { tool ->
            tool.routePatterns.forEach { pattern ->
                assertTrue("$pattern resolves to nothing", ToolCatalog.toolForRoute(pattern) != null)
            }
        }
        assertEquals("native_llama", ToolCatalog.toolForRoute("llama_chat/5/3")?.id)
        assertEquals("agent", ToolCatalog.toolForRoute("agent?conversationId=7&harnessTab=plan")?.id)
        assertEquals("agent", ToolCatalog.toolForRoute("agent_invocation/12")?.id)
        assertEquals("image_generation", ToolCatalog.toolForRoute("image_gen?startMode=1&tab=gallery")?.id)
        assertEquals("servers_hub", ToolCatalog.toolForRoute(SERVERS_HUB_ROUTE)?.id)
        assertEquals("web_servers", ToolCatalog.toolForRoute(Screen.AiServersHub.route)?.id)
        assertEquals("llama_scheduler", ToolCatalog.toolForRoute(Screen.LlamaScheduler.route)?.id)
        assertEquals("workflows", ToolCatalog.toolForRoute(Screen.Workflows.route)?.id)
        assertEquals("all_media_gallery", ToolCatalog.toolForRoute("all_media_gallery?type=audio&source=")?.id)
        assertEquals("global_search", ToolCatalog.toolForRoute("global_search")?.id)
        assertNull(ToolCatalog.toolForRoute(null))
        assertNull(ToolCatalog.toolForRoute(""))
        assertNull(ToolCatalog.toolForRoute(Screen.AIHub.route))
        assertNull(ToolCatalog.toolForRoute("unknown_route/1"))
    }

    @Test
    fun `soft studio catalog keeps task and infrastructure entry points discoverable`() {
        val byId = ToolCatalog.tools.associateBy { it.id }

        assertEquals(ToolCategory.ASSISTANTS, byId.getValue("native_llama").category)
        assertEquals(ToolCategory.ASSISTANTS, byId.getValue("agent").category)
        assertEquals(ToolCategory.ASSISTANTS, byId.getValue("knowledge").category)
        assertEquals(ToolCategory.ASSISTANTS, byId.getValue("llama_scheduler").category)
        assertEquals(ToolCategory.CREATE, byId.getValue("onnx_image_generation").category)
        assertEquals(ToolCategory.VOICE, byId.getValue("audio_workspace").category)
        assertEquals(ToolCategory.VOICE, byId.getValue("onnx_tts").category)
        assertEquals(ToolCategory.DOCUMENTS, byId.getValue("pdf_tools").category)
        assertEquals(ToolCategory.SHARE_NETWORK, byId.getValue("servers_hub").category)
        assertEquals(ToolCategory.SHARE_NETWORK, byId.getValue("distributed_llm").category)
        assertEquals(ToolCategory.SHARE_NETWORK, byId.getValue("distributed_media").category)
        assertEquals(ToolCategory.ADVANCED, byId.getValue("quadtrix").category)
        assertTrue("ONNX search keyword was dropped", byId.getValue("onnx_tts").keywords.contains("onnx"))
        assertTrue("File sharing is not searchable", byId.getValue("file_server").keywords.contains("share"))
    }

    @Test
    fun `visibility separates tiles from search only entries`() {
        val byId = ToolCatalog.tools.associateBy { it.id }
        listOf("native_llama", "agent", "knowledge", "onnx_tts", "servers_hub", "file_server").forEach {
            assertEquals("$it should be a tile", ToolVisibility.GRID, byId.getValue(it).visibility)
        }
        listOf("llama_scheduler", "model_library", "all_media_gallery", "generation_queue", "organizer", "workflow_dubbing", "settings_llm", "settings_about")
            .forEach { assertEquals("$it should be search only", ToolVisibility.SEARCH, byId.getValue(it).visibility) }
        assertEquals(ToolVisibility.HIDDEN, byId.getValue("chat").visibility)
        assertTrue(ToolCatalog.settingsPages.all { it.visibility == ToolVisibility.SEARCH })
        assertEquals(12, ToolCatalog.settingsPages.size)
        assertTrue(ToolCatalog.gridTools.none { it.isSettingsPage })
        assertTrue(ToolCatalog.searchableTools.none { it.visibility == ToolVisibility.HIDDEN || it.isSettingsPage })
        assertTrue(ToolCatalog.matchesRoute(Screen.Agent.route))
        assertTrue(ToolCatalog.matchesRoute(Screen.OnnxTts.route))
    }

    @Test
    fun `added in marks the current round entries`() {
        val ids = ToolCatalog.addedIn("0.9999").map { it.id }
        assertTrue(ids.containsAll(listOf("servers_hub", "global_search", "all_media_gallery")))
        assertEquals("0.9998", ToolCatalog.tools.single { it.id == "native_llama" }.addedIn)
        assertTrue(ToolCatalog.addedIn("0.0").isEmpty())
    }

    @Test
    fun `grid tiles use distinct icons`() {
        val duplicates = ToolCatalog.gridTools.groupBy { it.icon }.filterValues { it.size > 1 }
        assertTrue("Duplicate icons: ${duplicates.values.map { l -> l.map { it.id } }}", duplicates.isEmpty())
    }

    @Test
    fun `not tried picks unopened grid tools, stable per day and rotating`() {
        val opened = setOf("agent", "native_llama")
        val today = ToolDiscovery.notTried(ToolCatalog.gridTools, opened, daySeed = 20000L)
        assertEquals(3, today.size)
        assertTrue(today.none { it.id in opened })
        assertEquals(today.map { it.id }, ToolDiscovery.notTried(ToolCatalog.gridTools, opened, 20000L).map { it.id })
        val otherDays = (20001L..20010L).map { day -> ToolDiscovery.notTried(ToolCatalog.gridTools, opened, day).map { it.id } }
        assertTrue("Picks never rotate", otherDays.any { it != today.map { t -> t.id } })
        val excluded = ToolDiscovery.notTried(ToolCatalog.gridTools, opened, 20000L, excludeIds = today.map { it.id }.toSet())
        assertTrue(excluded.none { e -> today.any { it.id == e.id } })
        assertTrue(ToolDiscovery.notTried(ToolCatalog.gridTools, ToolCatalog.gridTools.map { it.id }.toSet(), 1L).isEmpty())
    }
}
