package com.example.llamadroid.service.titlegen

import com.example.llamadroid.service.ephemeral.EphemeralLlamaServer
import org.json.JSONArray
import org.json.JSONObject

/** Pure decisions for automatic chat titles; no Android or I/O dependencies so they stay unit-testable. */
internal object TitleGenerationLogic {
    const val SHORT_MAX_TOKENS = 24
    const val RETRY_MAX_TOKENS = 64
    const val MAX_TEXT_CHARS = 1500
    const val MAX_TITLE_CHARS = 60
    const val MAX_PENDING = 50

    const val INSTRUCTION = "Write a 3-6 word title for this conversation. Reply with the title only."

    /** True when the device can host the title model without pressuring the foreground app. */
    fun hasEnoughRam(availBytes: Long, lowMemory: Boolean, modelFileBytes: Long): Boolean =
        EphemeralLlamaServer.decideRam(availBytes, lowMemory, modelFileBytes).ok

    /** Idle-state mapping for the settings card when no work is running. */
    fun baselineStatus(enabled: Boolean, modelPath: String): TitleGenerationStatus = when {
        modelPath.isBlank() -> TitleGenerationStatus.NoModel
        !enabled -> TitleGenerationStatus.Disabled
        else -> TitleGenerationStatus.Idle
    }

    /** A chat is queued after any completed reply while it still has an automatic title and was not handled. */
    fun shouldEnqueue(
        title: String,
        defaultTitles: Set<String>,
        handledIds: Set<Long>,
        chatId: Long,
        userMessages: Int,
        assistantMessages: Int
    ): Boolean = chatId !in handledIds &&
        userMessages >= 1 && assistantMessages >= 1 &&
        isDefaultTitle(title, defaultTitles)

    /** At processing time the chat may have grown; only the default-title and not-handled rules remain. */
    fun stillEligible(
        title: String,
        defaultTitles: Set<String>,
        handledIds: Set<Long>,
        chatId: Long,
        userMessages: Int,
        assistantMessages: Int
    ): Boolean = chatId !in handledIds &&
        userMessages >= 1 && assistantMessages >= 1 &&
        isDefaultTitle(title, defaultTitles)

    fun isDefaultTitle(title: String, defaultTitles: Set<String>): Boolean {
        val clean = title.trim()
        return clean.isEmpty() || defaultTitles.any { it.equals(clean, ignoreCase = true) }
    }

    fun truncate(text: String, max: Int = MAX_TEXT_CHARS): String {
        val clean = text.trim()
        return if (clean.length <= max) clean else clean.substring(0, max).trimEnd() + "..."
    }

    fun buildUserPrompt(firstUser: String, firstAssistant: String): String =
        "$INSTRUCTION\n\nUser:\n${truncate(firstUser)}\n\nAssistant:\n${truncate(firstAssistant)}"

    /** Thinking is disabled the same way the chat service does it so Qwen3-style models answer directly. */
    fun buildRequestBody(prompt: String, maxTokens: Int = SHORT_MAX_TOKENS): String = JSONObject()
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        .put("reasoning_effort", "none")
        .put("max_tokens", maxTokens)
        .put("temperature", 0.3)
        .put("stream", false)
        .put("cache_prompt", false)
        .toString()

    /** Removes closed think blocks and any unterminated trailing one (truncated reasoning yields no title). */
    fun stripThinking(raw: String): String =
        raw.replace(Regex("(?is)<think>.*?</think>"), " ").replace(Regex("(?is)<think>.*$"), " ")

    /** Returns a one-line plain title, or null when nothing usable remains. */
    fun sanitizeTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var text = stripThinking(raw)
        text = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        text = text.replace(Regex("^(?i)title\\s*:\\s*"), "")
        text = text.replace(Regex("[*_`#>~]+"), "")
        text = text.trim().trim('"', '\'', '“', '”', '‘', '’', '«', '»').trim()
        text = text.trimEnd('.', ':', ';', ',').replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        if (text.length > MAX_TITLE_CHARS) {
            val cut = text.substring(0, MAX_TITLE_CHARS)
            text = cut.substringBeforeLast(' ', cut).trimEnd()
        }
        return text.takeIf { it.isNotEmpty() }
    }

    fun parseTitleResponse(body: String): String? = runCatching {
        val message = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        sanitizeTitle(message.optString("content"))
    }.getOrNull()
}
