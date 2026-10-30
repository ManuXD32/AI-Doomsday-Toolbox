package com.example.llamadroid.ui.easy

internal const val EASY_CHAT_TITLE_MAX = 40

/**
 * Title for an Easy chat from its first user message: whitespace collapsed, cut at
 * [EASY_CHAT_TITLE_MAX] characters with an ellipsis. Returns null when the chat already has a custom
 * title (anything not in [replaceableTitles], e.g. the model/card name or a default title) or the
 * message is blank.
 */
internal fun easyChatTitleFromMessage(
    message: String,
    currentTitle: String,
    replaceableTitles: Set<String>
): String? {
    val current = currentTitle.trim()
    if (current.isNotEmpty() && replaceableTitles.none { it.equals(current, ignoreCase = true) }) return null
    val flat = message.trim().replace(Regex("\\s+"), " ")
    if (flat.isEmpty()) return null
    return if (flat.length <= EASY_CHAT_TITLE_MAX) flat else flat.take(EASY_CHAT_TITLE_MAX).trimEnd() + "…"
}
