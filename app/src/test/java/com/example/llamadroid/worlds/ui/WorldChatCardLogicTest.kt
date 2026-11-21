package com.example.llamadroid.worlds.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorldChatCardLogicTest {
    private val world = "Here you go\n```html\n<html><head><title>Elephants</title></head><body>" +
        "<script type=\"module\">import * as THREE from 'three';</script></body></html>\n```"

    @Test
    fun `card shows only for finished messages with a three world`() {
        assertTrue(shouldShowWorldCard(world, streaming = false))
        assertFalse(shouldShowWorldCard(world, streaming = true))
        assertFalse(shouldShowWorldCard("```html\n<p>hello</p>\n```", streaming = false))
        assertFalse(shouldShowWorldCard("", streaming = false))
        assertFalse(shouldShowWorldCard("plain chat text", streaming = false))
    }

    @Test
    fun `title comes from title tag then first line then fallback`() {
        assertEquals("Elephants", worldTitleFrom(world, "x"))
        assertEquals("My zoo", worldTitleFrom("My zoo\n```js\nTHREE.Scene\n```", "x"))
        assertEquals("fallback", worldTitleFrom("```html\n<div></div>\n```", "fallback"))
        assertTrue(worldTitleFrom("a".repeat(200), "x").length <= 60)
    }

    @Test
    fun `world builder prompt profile has english and spanish strings`() {
        val res = listOf("values", "values-es")
        for (dir in res) {
            val xml = listOf(
                File("src/main/res/$dir/worlds_ui.xml"),
                File("app/src/main/res/$dir/worlds_ui.xml")
            ).first { it.exists() }.readText()
            assertTrue(dir, xml.contains("name=\"llama_prompt_profile_world_builder\""))
            assertTrue(dir, xml.contains("name=\"llama_prompt_profile_world_builder_prompt\""))
        }
    }
}
