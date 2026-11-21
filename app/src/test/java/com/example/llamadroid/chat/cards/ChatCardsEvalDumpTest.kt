package com.example.llamadroid.chat.cards

import com.example.llamadroid.service.NativeChatBasePrefix
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.service.NativeChatToolRuntime
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.buildLlamaServerChatRequestPayload
import com.google.gson.GsonBuilder
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dumps the stable system text and the tool list a native chat sends in the widest tool
 * configuration (cards OFF) so the host eval rig can replay the real prompt. Not a regression test.
 */
class ChatCardsEvalDumpTest {
    @Test
    fun dumpBasePrompt() {
        val config = NativeChatToolConfig.liteRtToolDefaults().copy(fileToolsEnabled = true, cardsEnabled = false)
        val system = NativeChatBasePrefix.stableText(config)
        val tools = NativeChatToolRuntime().availableTools(config)
        val payload = buildLlamaServerChatRequestPayload(
            messages = listOf(
                OllamaService.ChatMessage(role = "system", content = system),
                OllamaService.ChatMessage(role = "user", content = "hi")
            ),
            tools = tools,
            thinkingEnabled = false
        )
        @Suppress("UNCHECKED_CAST")
        val requestTools = payload["tools"] as List<Map<String, Any?>>
        assertTrue(system.isNotEmpty())
        assertTrue(requestTools.isNotEmpty())
        val out = linkedMapOf<String, Any?>(
            "preset" to "liteRtToolDefaults+fileTools (widest native chat tool set, cards off)",
            "system" to system,
            "tools" to requestTools
        )
        val dir = File("build/chat-cards-eval").apply { mkdirs() }
        File(dir, "base.json").writeText(GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(out))
        println("EVALDUMP tools=${requestTools.size} systemChars=${system.length}")
    }
}
