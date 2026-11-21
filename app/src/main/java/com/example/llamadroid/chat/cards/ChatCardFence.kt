package com.example.llamadroid.chat.cards

/**
 * The single rule that decides whether a markdown code fence is an interactive card. Both the markdown renderer
 * and the answer batcher use it so their card indexes always agree.
 */
object ChatCardFence {
    /** True when the first word of [language] is `card` (any case). */
    fun isLabeled(language: String): Boolean =
        language.trim().takeWhile { !it.isWhitespace() }.equals(CHAT_CARD_FENCE_LANGUAGE, ignoreCase = true)

    /**
     * True when the first word of [language] is `card` (any case), or when [language] is blank or `json` and
     * [code] is a JSON object with an explicit known `"type"` that parses into a card. Other fences (for example
     * a JSON schema `{"type":"object"}`) stay normal code blocks.
     */
    fun isCard(language: String, code: String): Boolean {
        if (isLabeled(language)) return true
        val lang = language.trim()
        if (lang.isNotEmpty() && !lang.equals("json", ignoreCase = true)) return false
        val body = code.trim()
        return body.startsWith("{") && ChatCardParser.hasKnownType(body) && ChatCardParser.parse(body) != null
    }

    /**
     * Models sometimes start the next card without closing the previous fence
     * (```card {…} ```card {…} ```), which would turn every second card into plain text. A "closing"
     * fence of a card block that itself carries the card language is really the next opening fence, so the
     * missing closing fence is inserted before it. Text without that pattern is returned unchanged.
     */
    fun normalize(raw: String): String {
        if (raw.indexOf(FENCE) < 0) return raw
        val out = StringBuilder(raw.length + 32)
        var cursor = 0
        while (cursor < raw.length) {
            val open = raw.indexOf(FENCE, cursor)
            if (open < 0) break
            val headerEnd = raw.indexOf('\n', open + FENCE.length)
            if (headerEnd < 0) break
            val close = raw.indexOf(FENCE, headerEnd + 1)
            if (close < 0) break
            val closeLineEnd = raw.indexOf('\n', close + FENCE.length).let { if (it < 0) raw.length else it }
            val reopens = isLabeled(raw.substring(open + FENCE.length, headerEnd)) &&
                isLabeled(raw.substring(close + FENCE.length, closeLineEnd))
            if (reopens) {
                out.append(raw, cursor, close).append(FENCE).append('\n')
                cursor = close
            } else {
                out.append(raw, cursor, close + FENCE.length)
                cursor = close + FENCE.length
            }
        }
        return out.append(raw, cursor, raw.length).toString()
    }

    private const val FENCE = "```"
}
