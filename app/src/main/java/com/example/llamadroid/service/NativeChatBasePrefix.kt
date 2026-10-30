package com.example.llamadroid.service

import java.security.MessageDigest

/**
 * Identity of the "chat base" KV cache: the preprocessed STABLE prefix (tool awareness text plus
 * tool definitions) that every new chat on one server, model and tool configuration starts with.
 *
 * The stable prefix never contains per-chat or changing text (the chat's own system prompt, the
 * knowledge-base routing guide, retrieved context, dates). Those
 * "dynamic" blocks are placed AFTER the stable text by [orderForBase], so llama-server reuses the
 * restored base as the common token prefix. Pure so it can be unit-tested; the hash is the only
 * thing that ends up in a file name, never the prompt text.
 */
object NativeChatBasePrefix {
    private const val SCOPE = "chat"
    private const val BLOCK_SEPARATOR = "\n\n"

    /**
     * Stable awareness text for [config]: the tool-awareness system messages plus the extension
     * awareness blocks ([extensionAwareness], e.g. DeepSeek delegation; they depend only on the
     * config), without the per-chat knowledge-base routing guide. Empty when no tools are enabled.
     */
    fun stableText(config: NativeChatToolConfig, extensionAwareness: List<String> = emptyList()): String =
        (nativeChatToolAwarenessMessages(config).map { it.content } + extensionAwareness)
            .map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(BLOCK_SEPARATOR).trim()

    /**
     * Reorders the leading system run of [messages] so the stable prefix comes first and every
     * dynamic block follows it. The dynamic blocks are the existing leading system messages (for
     * example the chat's own system prompt) followed by [extraDynamicBlocks] (routing guide,
     * extension awareness, transient context). By default everything is merged into ONE system
     * message (stable text first), because many chat templates reject several system messages;
     * with [dynamicAsSeparateMessage] the dynamic part becomes a second system message.
     * Non-system messages are untouched. Returns [messages] unchanged when both parts are blank.
     */
    fun orderForBase(
        messages: List<OllamaService.ChatMessage>,
        stableText: String,
        extraDynamicBlocks: List<String> = emptyList(),
        dynamicAsSeparateMessage: Boolean = false
    ): List<OllamaService.ChatMessage> {
        val leading = messages.takeWhile { it.role == "system" }
        val rest = messages.drop(leading.size)
        val stable = stableText.trim()
        val dynamic = (leading.map { it.content } + extraDynamicBlocks)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (stable.isEmpty() && dynamic.isEmpty()) return messages
        val head = if (dynamicAsSeparateMessage) {
            listOfNotNull(
                stable.takeIf { it.isNotEmpty() },
                dynamic.joinToString(BLOCK_SEPARATOR).takeIf { it.isNotEmpty() }
            ).map { OllamaService.ChatMessage(role = "system", content = it) }
        } else {
            listOf(
                OllamaService.ChatMessage(
                    role = "system",
                    content = (listOf(stable).filter { it.isNotEmpty() } + dynamic).joinToString(BLOCK_SEPARATOR)
                )
            )
        }
        return head + rest
    }

    /** Content-complete signature of a tool list; tool order matters because it changes the prefix. */
    fun toolsSignature(tools: List<AgentTool>): String = tools.joinToString("\u001e") { tool ->
        buildString {
            append(tool.name).append('\u001f').append(tool.description)
            append('\u001f').append(tool.parameters.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
            append('\u001f').append(tool.requiredParams.sorted().joinToString(","))
            append('\u001f').append(tool.schemaJson.orEmpty())
        }
    }

    /**
     * SHA-256 over the normalized server endpoint, the STABLE system text and the tools. The model
     * is deliberately not part of the hash: callers spell it differently (display name vs file
     * name), and the sidecar fingerprint (model path, size, mtime, context, KV types, template)
     * already rejects a base saved for another model.
     */
    @Suppress("UNUSED_PARAMETER")
    fun hash(baseUrl: String, model: String, systemText: String, tools: List<AgentTool>): String =
        sha256Hex(listOf(normalizePhysicalEndpoint(baseUrl), systemText.trim(), toolsSignature(tools)).joinToString("\u001d"))

    fun key(baseUrl: String, model: String, systemText: String, tools: List<AgentTool>): KvCacheKey.Base =
        KvCacheKey.Base(SCOPE, hash(baseUrl, model, systemText, tools))

    /** The prefix-only request body: the stable system message and a one-word user turn. */
    fun prefixRequestMessages(stableText: String): List<OllamaService.ChatMessage> = buildList {
        if (stableText.isNotBlank()) add(OllamaService.ChatMessage(role = "system", content = stableText.trim()))
        add(OllamaService.ChatMessage(role = "user", content = "Hi"))
    }

    /**
     * Suspending 1-token request that processes only the stable prefix on [slotId]. Throws when the
     * server rejects it, so callers can report a failed request instead of saving an empty slot.
     */
    fun prefixSender(
        baseUrl: String,
        model: String?,
        stableText: String,
        tools: List<AgentTool>
    ): suspend (Int) -> Unit = { slot ->
        LlamaServerChatService().chatWithToolsStreaming(
            baseUrl = baseUrl,
            messages = prefixRequestMessages(stableText),
            tools = tools,
            modelLabel = model,
            maxTokens = 1,
            requestOptions = LlamaServerRequestOptions(slotId = slot)
        ).getOrThrow()
        Unit
    }

    /**
     * Hint for a chat model call. Null when there is nothing worth caching (no stable text and no
     * tools). A prefix-only [KvBaseHint.builder] rebuilds a missing base; the chat's own slot is
     * never saved as a base.
     */
    fun hint(
        baseUrl: String,
        model: String?,
        stableText: String,
        tools: List<AgentTool>,
        capture: Boolean = true
    ): KvBaseHint? {
        if (stableText.isBlank() && tools.isEmpty()) return null
        val modelName = model?.takeIf { it.isNotBlank() }.orEmpty()
        return KvBaseHint(
            key = key(baseUrl, modelName, stableText, tools),
            capture = capture,
            builder = prefixSender(baseUrl, modelName.ifBlank { null }, stableText, tools)
        )
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
