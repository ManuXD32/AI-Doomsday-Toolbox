package com.example.llamadroid.service.vision

import com.example.llamadroid.data.model.LlamaMessageEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFallbackLogicTest {
    private fun msg(id: Long, role: String = "user", content: String = "hi", image: String? = null) =
        LlamaMessageEntity(id = id, chatId = 1, role = role, content = content, imagePath = image)

    @Test
    fun `vision servers keep messages untouched`() {
        val list = listOf(msg(1, image = "/a.png"))
        assertSame(list, ImageFallbackLogic.apply(list, supportsVision = true, descriptions = mapOf(1L to "x")))
    }

    @Test
    fun `non vision servers lose images and get the description as text`() {
        val list = listOf(msg(1, content = "what is this?", image = "/a.png"), msg(2, role = "assistant", content = "ok"))
        val out = ImageFallbackLogic.apply(list, supportsVision = false, descriptions = mapOf(1L to " a cat "))
        assertNull(out[0].imagePath)
        assertEquals("what is this?\n\n${ImageFallbackLogic.DESCRIPTION_HEADER}\na cat", out[0].content)
        assertEquals(list[1], out[1])
    }

    @Test
    fun `image without description is dropped silently and empty text becomes the block`() {
        val out = ImageFallbackLogic.apply(
            listOf(msg(1, content = "", image = "/a.png"), msg(2, content = "q", image = "/b.png")),
            supportsVision = false,
            descriptions = mapOf(1L to "desc")
        )
        assertEquals("${ImageFallbackLogic.DESCRIPTION_HEADER}\ndesc", out[0].content)
        assertEquals("q", out[1].content)
        assertTrue(out.all { it.imagePath == null })
    }

    @Test
    fun `pending skips described messages with the same path and caps to the newest`() {
        val messages = (1L..8L).map { msg(it, image = "/$it.png") } + msg(9, content = "no image")
        val pending = ImageFallbackLogic.pending(messages, mapOf(8L to "/8.png", 7L to "/old.png"), limit = 3)
        assertEquals(listOf(5L, 6L, 7L), pending.map { it.id })
    }

    @Test
    fun `request body carries the image and the thinking switch`() {
        val off = JSONObject(ImageFallbackLogic.buildRequestBody("data:image/png;base64,AA", thinking = false))
        assertEquals(false, off.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        assertEquals("none", off.getString("reasoning_effort"))
        assertEquals(ImageFallbackLogic.MAX_TOKENS, off.getInt("max_tokens"))
        val parts = off.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        val on = JSONObject(ImageFallbackLogic.buildRequestBody("u", thinking = true))
        assertTrue(on.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        assertTrue(!on.has("reasoning_effort"))
    }

    @Test
    fun `parse strips reasoning and rejects empty replies`() {
        fun body(text: String) = JSONObject().put(
            "choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)))
        ).toString()
        assertEquals("A red car.", ImageFallbackLogic.parseDescription(body("<think>hmm</think>\nA red car.\n")))
        assertNull(ImageFallbackLogic.parseDescription(body("<think>unfinished")))
        assertNull(ImageFallbackLogic.parseDescription("not json"))
    }
}
