package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatBasePrefixTest {
    private val url = "http://127.0.0.1:8080"
    private fun sys(text: String) = OllamaService.ChatMessage(role = "system", content = text)
    private fun user(text: String) = OllamaService.ChatMessage(role = "user", content = text)
    private val config = NativeChatToolConfig(toolsEnabled = true, calculatorEnabled = true)

    @Test
    fun `stable text is empty without tools and ignores routing guide and extensions`() {
        assertEquals("", NativeChatBasePrefix.stableText(NativeChatToolConfig()))
        val stable = NativeChatBasePrefix.stableText(config)
        assertTrue(stable.isNotBlank())
        // The stable text is the awareness without guide/extension blocks.
        val withExtras = nativeChatToolAwarenessMessages(config, "guide-x", listOf("ext-y"))
            .joinToString("\n\n") { it.content.trim() }
        assertTrue(withExtras.startsWith(stable))
    }

    @Test
    fun `dynamic blocks follow the stable text in one system message`() {
        val stable = "STABLE"
        val out = NativeChatBasePrefix.orderForBase(
            listOf(sys("chat prompt"), user("hello")), stable, listOf("routing guide", "  ", "ext block")
        )
        assertEquals(2, out.size)
        assertEquals("system", out[0].role)
        assertEquals("STABLE\n\nchat prompt\n\nrouting guide\n\next block", out[0].content)
        assertEquals("hello", out[1].content)
    }

    @Test
    fun `dynamic blocks can be a separate second system message`() {
        val out = NativeChatBasePrefix.orderForBase(
            listOf(sys("chat prompt"), user("hi")), "STABLE", listOf("guide"), dynamicAsSeparateMessage = true
        )
        assertEquals(listOf("system", "system", "user"), out.map { it.role })
        assertEquals("STABLE", out[0].content)
        assertEquals("chat prompt\n\nguide", out[1].content)
    }

    @Test
    fun `no stable and no dynamic text leaves messages untouched`() {
        val input = listOf(user("hi"))
        assertEquals(input, NativeChatBasePrefix.orderForBase(input, "", emptyList()))
    }

    @Test
    fun `chats with different dynamic blocks share the same stable prefix and hash`() {
        val stable = NativeChatBasePrefix.stableText(config)
        val a = NativeChatBasePrefix.orderForBase(listOf(sys("prompt A"), user("1")), stable, listOf("guide A"))
        val b = NativeChatBasePrefix.orderForBase(listOf(sys("prompt B"), user("2")), stable, emptyList())
        assertTrue(a[0].content.startsWith(stable))
        assertTrue(b[0].content.startsWith(stable))
        val tools = listOf(AgentTool("t", "d", mapOf("a" to "b")))
        assertEquals(
            NativeChatBasePrefix.key(url, "m", stable, tools),
            NativeChatBasePrefix.key(url, "m", stable, tools)
        )
    }

    @Test
    fun `tool or stable text change changes the hash but dynamic text is not part of it`() {
        val stable = NativeChatBasePrefix.stableText(config)
        val t1 = listOf(AgentTool("t", "d", mapOf("a" to "b")))
        val t2 = listOf(AgentTool("t", "d2", mapOf("a" to "b")))
        val base = NativeChatBasePrefix.key(url, "m", stable, t1)
        assertNotEquals(base, NativeChatBasePrefix.key(url, "m", stable, t2))
        assertNotEquals(base, NativeChatBasePrefix.key(url, "m", "$stable more", t1))
        assertNotEquals(base, NativeChatBasePrefix.key("http://127.0.0.1:9090", "m", stable, t1))
    }

    @Test
    fun `key ignores model spelling and host aliases so prepare and chats agree`() {
        val stable = NativeChatBasePrefix.stableText(config)
        val tools = listOf(AgentTool("t", "d", mapOf("a" to "b")))
        val base = NativeChatBasePrefix.key(url, "Gemma 4 E4B", stable, tools)
        assertEquals(base, NativeChatBasePrefix.key(url, "gemma-4-e4b.gguf", stable, tools))
        assertEquals(base, NativeChatBasePrefix.key("http://localhost:8080/", "m", stable, tools))
    }

    @Test
    fun `extension awareness is part of the stable text`() {
        val plain = NativeChatBasePrefix.stableText(config)
        val withExtension = NativeChatBasePrefix.stableText(config, listOf("DeepSeek delegation: rules"))
        assertTrue(withExtension.startsWith(plain))
        assertTrue(withExtension.endsWith("DeepSeek delegation: rules"))
        assertEquals(plain, NativeChatBasePrefix.stableText(config, listOf("  ")))
    }

    @Test
    fun `hint is null with nothing to cache and carries a builder otherwise`() {
        assertNull(NativeChatBasePrefix.hint(url, "m", "", emptyList()))
        val tools = listOf(AgentTool("t", "d", mapOf("a" to "b")))
        val hint = NativeChatBasePrefix.hint(url, "m", "stable", tools)
        assertTrue(hint != null && hint.builder != null && hint.capture)
        assertEquals(NativeChatBasePrefix.key(url, "m", "stable", tools), hint!!.key)
    }

    @Test
    fun `prefix request contains only the stable text and a short user turn`() {
        val msgs = NativeChatBasePrefix.prefixRequestMessages("STABLE")
        assertEquals(listOf("system", "user"), msgs.map { it.role })
        assertEquals("STABLE", msgs[0].content)
        assertEquals(listOf("user"), NativeChatBasePrefix.prefixRequestMessages(" ").map { it.role })
        assertFalse(msgs.any { it.content.contains("chat prompt") })
    }
}
