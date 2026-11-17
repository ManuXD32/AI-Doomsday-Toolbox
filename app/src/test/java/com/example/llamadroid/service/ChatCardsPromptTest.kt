package com.example.llamadroid.service

import com.example.llamadroid.chat.cards.ChatCardsAwareness
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.service.chat.ChatCardsPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Interactive cards flag: config round trip and the stable-prefix / plain-prompt wiring. */
class ChatCardsPromptTest {
    @Test
    fun `cards flag defaults off and survives param round trip`() {
        assertFalse(NativeChatToolConfig().cardsEnabled)
        assertFalse(NativeChatToolConfig.fromParams(emptyMap()).cardsEnabled)
        val on = NativeChatToolConfig(cardsEnabled = true)
        assertEquals(true, on.toParamMap()[NativeChatToolConfig.KEY_CARDS_ENABLED])
        assertTrue(NativeChatToolConfig.fromParams(on.toParamMap()).cardsEnabled)
        assertFalse(NativeChatToolConfig.fromParams(NativeChatToolConfig().toParamMap()).cardsEnabled)
    }

    @Test
    fun `stable prefix contains the cards text only when enabled`() {
        assertEquals("", NativeChatBasePrefix.stableText(NativeChatToolConfig()))
        val on = NativeChatBasePrefix.stableText(NativeChatToolConfig(cardsEnabled = true))
        assertEquals(ChatCardsAwareness.TEXT.trim(), on)
        val withTools = NativeChatBasePrefix.stableText(
            NativeChatToolConfig(toolsEnabled = true, calculatorEnabled = true, cardsEnabled = true)
        )
        assertTrue(withTools.endsWith(ChatCardsAwareness.TEXT.trim()))
        assertFalse(
            NativeChatBasePrefix.stableText(NativeChatToolConfig(toolsEnabled = true, calculatorEnabled = true))
                .contains(ChatCardsAwareness.TEXT.trim())
        )
    }

    @Test
    fun `plain system prompt puts the cards text first and is unchanged when off`() {
        val off = NativeChatToolConfig()
        val on = NativeChatToolConfig(cardsEnabled = true)
        assertEquals("Be brief", ChatCardsPrompt.systemPrompt("Be brief", off))
        assertEquals(null, ChatCardsPrompt.systemPrompt(null, off))
        assertEquals(ChatCardsAwareness.TEXT, ChatCardsPrompt.systemPrompt(null, on))
        assertEquals(ChatCardsAwareness.TEXT + "\n\nBe brief", ChatCardsPrompt.systemPrompt("  Be brief ", on))
        val chat = LlamaChatEntity(title = "t", systemPrompt = "Be brief")
        assertSame(chat, ChatCardsPrompt.forPlainTurn(chat, off))
        assertTrue(ChatCardsPrompt.forPlainTurn(chat, on).systemPrompt!!.startsWith(ChatCardsAwareness.TEXT))
    }

    private val on = NativeChatToolConfig(cardsEnabled = true)

    @Test
    fun `image tool note only with cards and the tools signature differs`() {
        val base = NativeChatToolConfig(toolsEnabled = true, imageGenerationEnabled = true)
        val runtime = NativeChatToolRuntime()
        fun image(c: NativeChatToolConfig) = runtime.availableTools(c).first { it.name == NativeChatToolRuntime.TOOL_GENERATE_IMAGE }
        val off = image(base)
        val onTool = image(base.copy(cardsEnabled = true))
        assertFalse(off.description.contains(ChatCardsAwareness.IMAGE_TOOL_NOTE))
        assertTrue(onTool.description.endsWith(ChatCardsAwareness.IMAGE_TOOL_NOTE))
        assertEquals(off.description + " " + ChatCardsAwareness.IMAGE_TOOL_NOTE, onTool.description)
        assertTrue(NativeChatBasePrefix.toolsSignature(listOf(off)) != NativeChatBasePrefix.toolsSignature(listOf(onTool)))
    }
}
