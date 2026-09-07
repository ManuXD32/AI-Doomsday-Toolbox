package com.example.llamadroid.ui.navigation

import com.example.llamadroid.tama.adventure.DungeonType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalRouteResolverTest {

    @Test
    fun `canonicalizes legacy routes`() {
        assertEquals(Screen.ModelHub.route, ExternalRouteResolver.resolveRoute(" models "))
        assertEquals(Screen.Stats.route, ExternalRouteResolver.resolveRoute("stats"))
        assertEquals(Screen.ZimManager.route, ExternalRouteResolver.resolveRoute("kiwix_hub"))
        assertEquals(
            "${Screen.ImageGen.route}?startMode=2",
            ExternalRouteResolver.resolveRoute(Screen.ImageGenUpscale.route)
        )
    }

    @Test
    fun `distinguishes no route from a rejected route`() {
        assertEquals(ExternalRouteResolution.NoRoute, ExternalRouteResolver.resolve(null))
        assertEquals(ExternalRouteResolution.Rejected, ExternalRouteResolver.resolve(" "))
        assertEquals(ExternalRouteResolution.Rejected, ExternalRouteResolver.resolve("missing"))
    }

    @Test
    fun `accepts current static and producer routes`() {
        listOf(
            Screen.Agent.route,
            Screen.Tama.route,
            Screen.NotesManager.route,
            Screen.LlamaScheduler.route,
            Screen.Dataset.route
        ).forEach { route ->
            assertEquals(route, ExternalRouteResolver.resolveRoute(route))
        }
        assertEquals(
            Screen.DatasetProject.createRoute(42),
            ExternalRouteResolver.resolveRoute(Screen.DatasetProject.createRoute(42))
        )
        assertEquals(
            Screen.LlamaChat.createRoute(7, -1),
            ExternalRouteResolver.resolveRoute(Screen.LlamaChat.createRoute(7, -1))
        )
        assertEquals(
            Screen.LlamaChatList.createFolderRoute(8),
            ExternalRouteResolver.resolveRoute(Screen.LlamaChatList.createFolderRoute(8))
        )
        DungeonType.entries.forEach { dungeonType ->
            val route = Screen.Adventure.createRoute(dungeonType.name)
            assertEquals(route, ExternalRouteResolver.resolveRoute(route))
        }
        (0..4).forEach { mode ->
            val route = Screen.ImageGen.createRoute(mode)
            assertEquals(route, ExternalRouteResolver.resolveRoute(route))
        }
    }

    @Test
    fun `rejects malformed or unsupported external routes`() {
        listOf(
            null,
            "",
            "  ",
            "qa_route_does_not_exist",
            "models/extra",
            "stats?unexpected=true",
            "kiwix_hub/extra",
            "chat?port=0",
            "chat?port=65536",
            "chat?port=abc",
            "image_gen?startMode=5",
            "image_gen?startMode=",
            "dataset_project",
            "dataset_project/0",
            "dataset_project/-1",
            "dataset_project/999999999999999999999999999999",
            "llama_chat_list/folder/0",
            "llama_chat_list/folder/999999999999999999999999999999",
            "llama_chat/0/-1",
            "llama_chat/7/0",
            "llama_chat/999999999999999999999999999999/-1",
            "llama_chat/7/3/extra",
            "adventure/not-a-dungeon",
            "quadtrix_webui/https%3A%2F%2Fexample.com",
            "termux_webview/https%3A%2F%2Fexample.com/title/tool"
        ).forEach { route ->
            assertNull("Expected route to be rejected: $route", ExternalRouteResolver.resolveRoute(route))
        }
    }
}
