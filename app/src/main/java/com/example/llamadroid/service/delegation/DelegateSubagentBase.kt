package com.example.llamadroid.service.delegation

import com.example.llamadroid.service.AgentTool
import com.example.llamadroid.service.KvBaseHint
import com.example.llamadroid.service.KvCacheKey
import com.example.llamadroid.service.LlamaServerChatService
import com.example.llamadroid.service.LlamaServerRequestOptions
import com.example.llamadroid.service.NativeChatBasePrefix
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.normalizePhysicalEndpoint
import java.security.MessageDigest

/**
 * Shared KV base cache of the DeepSeek subagent: its system prompt and tool list are identical for
 * every task, so one prefix-only base per server/model is restored by every new task.
 *
 * The thinking flag is part of the key because it changes the rendered chat-template kwargs
 * (`enable_thinking`) and therefore the prefix tokens. Callers only pass a hint when the task's
 * own KV cache is persistent (`subagentKvCacheEnabled` on a loopback server); otherwise there is
 * no coordinator to restore a base. The builder never sends a task's own messages.
 */
object DelegateSubagentBase {
    private const val SCOPE = "delegate"
    private const val PREFIX_USER_MESSAGE = "Hi"

    /** Thinking flag the loop uses for a server: its default API param `enable_thinking` (default on). */
    fun thinkingFor(defaultApiParams: String?): Boolean =
        (parseDelegationParams(defaultApiParams)["enable_thinking"] as? Boolean) ?: true

    /** Hash over the normalized endpoint, the system text, the tools and the thinking flag. */
    internal fun hash(baseUrl: String, systemText: String, tools: List<AgentTool>, thinkingEnabled: Boolean): String {
        val material = listOf(
            normalizePhysicalEndpoint(baseUrl),
            systemText.trim(),
            NativeChatBasePrefix.toolsSignature(tools),
            if (thinkingEnabled) "think=1" else "think=0"
        ).joinToString("\u001d")
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Base hint for a subagent model call on [baseUrl], or null when caching does not apply. */
    fun hint(baseUrl: String, modelName: String?, thinkingEnabled: Boolean): KvBaseHint? {
        if (baseUrl.isBlank()) return null
        val system = DelegateSubagentPrompt.systemPrompt()
        val tools = DelegateSubagentToolDefs.all()
        if (system.isBlank() && tools.isEmpty()) return null
        val label = modelName?.takeIf { it.isNotBlank() }
        return KvBaseHint(
            key = KvCacheKey.Base(SCOPE, hash(baseUrl, system, tools, thinkingEnabled)),
            capture = true,
            builder = { slot ->
                LlamaServerChatService().chatWithToolsStreaming(
                    baseUrl = baseUrl,
                    messages = listOf(
                        OllamaService.ChatMessage(role = "system", content = system),
                        OllamaService.ChatMessage(role = "user", content = PREFIX_USER_MESSAGE)
                    ),
                    tools = tools,
                    modelLabel = label,
                    thinkingEnabled = thinkingEnabled,
                    maxTokens = 1,
                    requestOptions = LlamaServerRequestOptions(slotId = slot, streamReadTimeoutMs = 0)
                ).getOrThrow()
                Unit
            }
        )
    }
}
