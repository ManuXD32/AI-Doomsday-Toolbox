package com.example.llamadroid.harness

import org.json.JSONArray
import org.json.JSONObject

/**
 * Makes the DeepSeek Harness system prompt byte-identical across runtime restarts and projects so
 * a prepared llama-server KV base (system prompt + tools, about 10k tokens) can be restored.
 *
 * The pinned upstream prompt ends with two lines that change: the Web GUI orientation paragraph
 * (it carries the loopback port, new on every runtime start) and the persona suffix
 * "Your working directory is <cwd>." (changes per project). This moves those lines, verbatim, out
 * of the FIRST system message into a "[Runtime context]" block that is prepended to the first
 * non-system message when that is a user message, so the model still sees both values right after
 * the system prompt. Nothing is inserted as a new message (strict-turn templates such as Gemma's
 * reject consecutive same-role messages). When there is no user message to carry the block the
 * request is returned unchanged. Pure, deterministic and idempotent. The plugin JS is never edited.
 */
object HarnessPromptNormalizer {
    const val RUNTIME_CONTEXT_HEADER = "[Runtime context]"

    private const val WEB_GUI_PREFIX = "You are interacting with the user through the DeepSeek Harness Web GUI at "
    private val CWD_LINE = Regex("""^Your working directory is .+\.$""")
    private val BLANK_RUNS = Regex("\n{3,}")

    /** Result of [extract]: the system text without the changing lines and the removed lines. */
    internal data class Extraction(val text: String, val moved: List<String>)

    internal fun extract(text: String): Extraction {
        val moved = ArrayList<String>()
        val kept = text.split("\n").filter { line ->
            val trimmed = line.trim()
            val isChanging = trimmed.startsWith(WEB_GUI_PREFIX) || CWD_LINE.matches(trimmed)
            if (isChanging) moved += trimmed
            !isChanging
        }
        if (moved.isEmpty()) return Extraction(text, emptyList())
        val cleaned = kept.joinToString("\n").replace(BLANK_RUNS, "\n\n").trim()
        return Extraction(cleaned, moved)
    }

    /** Normalizes `request.messages`; returns [request] itself when nothing changes. */
    fun normalizeRequest(request: JSONObject): JSONObject {
        val messages = request.optJSONArray("messages") ?: return request
        val normalized = normalize(messages)
        if (normalized === messages) return request
        return JSONObject(request.toString()).put("messages", normalized)
    }

    /** Normalizes an OpenAI `messages` array; returns [messages] itself when nothing changes. */
    fun normalize(messages: JSONArray): JSONArray {
        val systemIndex = (0 until messages.length()).firstOrNull { messages.optJSONObject(it)?.optString("role") == "system" }
            ?: return messages
        if (systemIndex != 0) return messages
        val system = messages.optJSONObject(0) ?: return messages
        val userIndex = (1 until messages.length()).firstOrNull { messages.optJSONObject(it)?.optString("role") != "system" }
            ?: return messages
        val user = messages.optJSONObject(userIndex) ?: return messages
        if (user.optString("role") != "user") return messages

        val moved = ArrayList<String>()
        val newSystemContent: Any = when (val content = system.opt("content")) {
            is String -> extract(content).also { moved += it.moved }.text
            is JSONArray -> {
                val parts = JSONArray()
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i)
                    if (part != null && part.optString("type") == "text") {
                        val result = extract(part.optString("text"))
                        moved += result.moved
                        parts.put(JSONObject(part.toString()).put("text", result.text))
                    } else {
                        parts.put(content.opt(i))
                    }
                }
                parts
            }
            else -> return messages
        }
        if (moved.isEmpty()) return messages

        val block = RUNTIME_CONTEXT_HEADER + "\n" + moved.joinToString("\n")
        val newUser = JSONObject(user.toString())
        when (val content = user.opt("content")) {
            is String -> newUser.put("content", block + "\n\n" + content)
            is JSONArray -> {
                val parts = JSONArray().put(JSONObject().put("type", "text").put("text", block))
                for (i in 0 until content.length()) parts.put(content.opt(i))
                newUser.put("content", parts)
            }
            else -> return messages
        }
        val out = JSONArray()
        for (i in 0 until messages.length()) {
            when (i) {
                0 -> out.put(JSONObject(system.toString()).put("content", newSystemContent))
                userIndex -> out.put(newUser)
                else -> out.put(messages.opt(i))
            }
        }
        return out
    }
}
