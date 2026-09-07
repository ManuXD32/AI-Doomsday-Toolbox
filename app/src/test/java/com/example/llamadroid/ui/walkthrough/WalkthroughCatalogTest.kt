package com.example.llamadroid.ui.walkthrough

import com.example.llamadroid.ui.ai.ToolCatalog
import com.example.llamadroid.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkthroughCatalogTest {
    @Test
    fun `catalog has ten uniquely addressable chapters`() {
        assertEquals(10, WalkthroughCatalog.chapters.size)
        val ids = WalkthroughCatalog.chapters.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertNotNull("Missing chapter lookup for $id", WalkthroughCatalog.chapter(id))
        }
        assertEquals(null, WalkthroughCatalog.chapter("missing"))
    }

    @Test
    fun `every ToolCatalog tool appears in exactly one tool lesson`() {
        val lessons = WalkthroughCatalog.chapters
            .flatMap { it.lessons }
            .filter { it.toolId != null }
        val toolIds = ToolCatalog.tools.map { it.id }

        assertEquals(29, toolIds.size)
        assertEquals(toolIds.sorted(), lessons.mapNotNull { it.toolId }.sorted())
        assertEquals(lessons.size, lessons.mapNotNull { it.toolId }.toSet().size)
        lessons.forEach { lesson ->
            val tool = ToolCatalog.tools.single { it.id == lesson.toolId }
            assertEquals("Title must follow ToolCatalog for ${lesson.id}", tool.titleRes, lesson.titleRes)
            assertEquals("Route must follow ToolCatalog for ${lesson.id}", tool.route, lesson.route)
        }
    }

    @Test
    fun `extra lessons point to real Tama and help routes`() {
        val lessons = WalkthroughCatalog.chapters
            .flatMap { it.lessons }
            .associateBy { it.id }

        assertEquals(Screen.Tama.route, lessons.getValue("tama_room").route)
        assertEquals(Screen.Tama.route, lessons.getValue("tama_care").route)
        assertEquals(Screen.Store.route, lessons.getValue("tama_shop").route)
        assertEquals(Screen.Farm.route, lessons.getValue("tama_farm").route)
        assertEquals(Screen.Arcade.route, lessons.getValue("tama_arcade").route)
        assertEquals(Screen.AdventureGate.route, lessons.getValue("tama_adventures").route)
        assertEquals(Screen.TamaGallery.route, lessons.getValue("tama_gallery").route)
        assertEquals(Screen.Settings.route, lessons.getValue("settings").route)
        assertEquals("about", lessons.getValue("about").route)
        assertEquals(Screen.Logs.route, lessons.getValue("diagnostics").route)
        assertTrue(lessons.values.all { it.toolId == null || it.id == it.toolId })
    }

    @Test
    fun `lessons use only bundled preview keys and nonempty routes`() {
        val allowedPreviewKeys = setOf(
            "home", "tools", "library", "create", "tama", "farm", "arcade", "adventures", "gallery"
        )

        WalkthroughCatalog.chapters
            .flatMap { chapter ->
                assertTrue("Chapter ${chapter.id} has no lessons", chapter.lessons.isNotEmpty())
                chapter.lessons
            }
            .forEach { lesson ->
                assertTrue("Blank route for ${lesson.id}", lesson.route.isNotBlank())
                assertTrue("Unsupported preview key for ${lesson.id}", lesson.previewKey in allowedPreviewKeys)
                assertTrue("Blank id for ${lesson.id}", lesson.id.isNotBlank())
                assertTrue("Missing body resource for ${lesson.id}", lesson.bodyRes != 0)
            }
    }

    @Test
    fun `every lesson id has an actual screenshot preview`() {
        val lessons = WalkthroughCatalog.chapters.flatMap { it.lessons }

        assertEquals(39, lessons.size)
        assertEquals(lessons.size, lessons.map { it.id }.toSet().size)
        lessons.forEach { lesson ->
            assertNotNull("Missing screenshot preview for ${lesson.id}", lessonPreviewResource(lesson.id))
        }
    }
}
