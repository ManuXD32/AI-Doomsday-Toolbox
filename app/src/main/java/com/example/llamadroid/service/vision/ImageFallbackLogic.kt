package com.example.llamadroid.service.vision

import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.service.titlegen.TitleGenerationLogic
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure rules for chats whose model cannot see images: images never reach such a server (they stay
 * in Room and in the chat UI), and a stored description replaces them as plain text.
 */
object ImageFallbackLogic {
    const val DESCRIPTION_HEADER =
        "[Image description — generated because you cannot see images; do not mention this to the user]"
    const val PROMPT =
        "Describe this image in great detail: objects, people, text (transcribe it), layout, colors, " +
            "style and anything notable. Be factual."
    const val MAX_IMAGES_PER_TURN = 6
    const val MAX_TOKENS = 700
    const val MAX_TOKENS_THINKING = 2400

    fun hasImage(message: LlamaMessageEntity): Boolean = !message.imagePath.isNullOrBlank()

    fun descriptionBlock(description: String): String = "$DESCRIPTION_HEADER\n${description.trim()}"

    /**
     * Messages that still need a description: they carry an image and no stored description for
     * that same path. Newest first, capped at [limit].
     */
    fun pending(
        messages: List<LlamaMessageEntity>,
        storedPathsByMessage: Map<Long, String>,
        limit: Int = MAX_IMAGES_PER_TURN
    ): List<LlamaMessageEntity> = messages
        .filter { !it.isError && hasImage(it) && storedPathsByMessage[it.id] != it.imagePath }
        .takeLast(limit)

    /**
     * Returns [messages] unchanged for vision-capable servers. Otherwise every image is removed from
     * the request copy and, when [descriptions] has an entry for the message, the description block
     * is appended to its text.
     */
    fun apply(
        messages: List<LlamaMessageEntity>,
        supportsVision: Boolean,
        descriptions: Map<Long, String>
    ): List<LlamaMessageEntity> {
        if (supportsVision) return messages
        return messages.map { message ->
            if (!hasImage(message)) return@map message
            val description = descriptions[message.id]?.trim().orEmpty()
            val content = if (description.isEmpty()) {
                message.content
            } else {
                listOf(message.content.trim(), descriptionBlock(description))
                    .filter { it.isNotEmpty() }
                    .joinToString("\n\n")
            }
            message.copy(imagePath = null, content = content)
        }
    }

    fun buildRequestBody(dataUrl: String, thinking: Boolean): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", PROMPT))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl)))
        val body = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", thinking))
            .put("max_tokens", if (thinking) MAX_TOKENS_THINKING else MAX_TOKENS)
            .put("temperature", 0.2)
            .put("stream", false)
            .put("cache_prompt", false)
        if (!thinking) body.put("reasoning_effort", "none")
        return body.toString()
    }

    /** Plain description text with reasoning blocks removed, or null when nothing usable came back. */
    fun parseDescription(body: String): String? = runCatching {
        val message = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        TitleGenerationLogic.stripThinking(message.optString("content")).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
