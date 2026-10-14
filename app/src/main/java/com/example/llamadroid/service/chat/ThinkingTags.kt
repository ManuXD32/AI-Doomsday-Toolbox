package com.example.llamadroid.service.chat

private val ThinkStartTags = listOf("<think>", "<|think|>", "<thought>", "<Thought>", "<Think>")
private val ThinkEndTags = listOf("</think>", "</|think|>", "</thought>", "</Thought>", "</Think>")

/**
 * Splits inline `<think>` blocks off [text]: returns (visible content, reasoning). [dedicated] is
 * reasoning already received on a separate channel and is prepended to the reasoning.
 */
internal fun extractThinkingTags(text: String, dedicated: String = ""): Pair<String, String> {
    var startTag: String? = null
    var startIndex = -1
    for (tag in ThinkStartTags) {
        val idx = text.indexOf(tag, ignoreCase = true)
        if (idx != -1 && (startIndex == -1 || idx < startIndex)) {
            startIndex = idx
            startTag = tag
        }
    }
    if (startIndex == -1) {
        return Pair(text, dedicated.trim())
    }
    var endTag: String? = null
    var endIndex = -1
    for (tag in ThinkEndTags) {
        val idx = text.indexOf(tag, startIndex + (startTag?.length ?: 0), ignoreCase = true)
        if (idx != -1 && (endIndex == -1 || idx < endIndex)) {
            endIndex = idx
            endTag = tag
        }
    }
    return if (endIndex != -1) {
        val thinking = text.substring(startIndex + (startTag?.length ?: 0), endIndex)
        val content = text.substring(0, startIndex) + text.substring(endIndex + (endTag?.length ?: 0))
        Pair(content.trim(), (dedicated + "\n" + thinking).trim())
    } else {
        val thinking = text.substring(startIndex + (startTag?.length ?: 0))
        val content = text.substring(0, startIndex)
        Pair(content.trim(), (dedicated + "\n" + thinking).trim())
    }
}
