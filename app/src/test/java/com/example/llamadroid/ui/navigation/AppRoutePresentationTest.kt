package com.example.llamadroid.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppRoutePresentationTest {
    @Test fun `only the four root destinations retain global navigation`() {
        AppRootDestination.entries.forEach {
            assertEquals(AppRoutePresentation(it, true), AppRoutePresentations.forRoute(it.route))
        }
        listOf("image_gen?startMode={startMode}&tab={tab}", "video_gen?tab={tab}",
            "llama_chat/{chatId}/{serverId}", "agent_workspace", "settings", "notes_manager",
            "farm", "kiwix_viewer", "llm_models").forEach {
            assertFalse("Deep task kept global chrome: $it", AppRoutePresentations.forRoute(it).isRoot)
        }
    }

    @Test fun `deep routes keep their conceptual parent without rendering global chrome`() {
        assertEquals(AppRootDestination.Library, AppRoutePresentations.forRoute("knowledge_chunk/42").parent)
        assertEquals(AppRootDestination.Tama, AppRoutePresentations.forRoute("adventure/FOREST").parent)
        assertEquals(AppRootDestination.Tools, AppRoutePresentations.forRoute("dataset_project/7").parent)
        assertEquals(null, AppRoutePresentations.forRoute("settings_general").parent)
        assertFalse(AppRoutePresentations.forRoute(null).isRoot)
    }

    @Test fun `short wide and large-text windows choose the accessible drawer before rail`() {
        assertEquals(AppNavigationLayout.Drawer, appNavigationLayout(320, 800, 1f))
        assertEquals(AppNavigationLayout.Bar, appNavigationLayout(360, 800, 1f))
        assertEquals(AppNavigationLayout.Bar, appNavigationLayout(411, 800, 1f))
        assertEquals(AppNavigationLayout.Rail, appNavigationLayout(600, 800, 1f))
        assertEquals(AppNavigationLayout.Rail, appNavigationLayout(840, 800, 1f))
        assertEquals(AppNavigationLayout.Drawer, appNavigationLayout(840, 320, 1f))
        listOf(320, 360, 411, 600, 840).forEach { width ->
            assertEquals(AppNavigationLayout.Drawer, appNavigationLayout(width, 800, 1.3f))
            assertEquals(AppNavigationLayout.Drawer, appNavigationLayout(width, 800, 2f))
        }
    }

    @Test fun `gallery links are validated without breaking legacy creation shortcuts`() {
        assertEquals("image_gen?startMode=0", Screen.ImageGen.createRoute())
        assertEquals("video_gen", Screen.VideoGen.createRoute())
        val imageGallery = Screen.ImageGen.createRoute(0, "gallery")
        val videoGallery = Screen.VideoGen.createRoute("gallery")
        assertEquals(imageGallery, ExternalRouteResolver.resolveRoute(imageGallery))
        assertEquals(videoGallery, ExternalRouteResolver.resolveRoute(videoGallery))
        assertEquals("library", ExternalRouteResolver.resolveRoute("library"))
        assertEquals("image_gen?startMode=2", ExternalRouteResolver.resolveRoute("image_gen_upscale"))
        listOf("image_gen?startMode=8&tab=gallery", "video_gen?tab=unknown",
            "image_gen?startMode=0&tab=gallery&tab=create", "video_gen?tab=gallery&url=https://example.com")
            .forEach { assertEquals(null, ExternalRouteResolver.resolveRoute(it)) }
    }

    @Test fun `home continuation links select one saved project and reject malformed identifiers`() {
        assertEquals("agent?conversationId=42", Screen.Agent.createRoute(42))
        assertEquals("agent", ExternalRouteResolver.resolveRoute("agent"))
        assertEquals("agent?conversationId=42", ExternalRouteResolver.resolveRoute(Screen.Agent.createRoute(42)))
        listOf("agent?conversationId=-1", "agent?conversationId=0", "agent?conversationId=42&command=run",
            "agent?conversationId=42/1", "agent?conversationId=9223372036854775808")
            .forEach { assertEquals(null, ExternalRouteResolver.resolveRoute(it)) }
    }
}
