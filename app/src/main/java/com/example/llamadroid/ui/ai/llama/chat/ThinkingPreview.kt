package com.example.llamadroid.ui.ai.llama.chat

/** Max chars of an expanded thinking block rendered inline (rest via the bounded viewer). */
internal const val THINKING_PREVIEW_CHARS = 4_000

/** While streaming only this tail is shown, so recomposition stays cheap. */
internal const val THINKING_STREAMING_TAIL_CHARS = 2_000

/**
 * Bounded view of a thinking block.
 * [text] is what to render, [hiddenChars] the number of omitted characters,
 * [plain] whether Markdown should be skipped (long text), [truncated] whether the full text needs the viewer.
 */
internal data class ThinkingPreview(
    val text: String,
    val hiddenChars: Int,
    val plain: Boolean,
    val truncated: Boolean
)

internal fun thinkingPreview(
    text: String,
    streaming: Boolean,
    previewChars: Int = THINKING_PREVIEW_CHARS,
    tailChars: Int = THINKING_STREAMING_TAIL_CHARS
): ThinkingPreview {
    if (streaming) {
        if (text.length <= tailChars) return ThinkingPreview(text, 0, text.length > previewChars, false)
        var start = text.length - tailChars
        if (Character.isLowSurrogate(text[start])) start += 1
        return ThinkingPreview(text.substring(start), start, plain = true, truncated = true)
    }
    if (text.length <= previewChars) return ThinkingPreview(text, 0, plain = false, truncated = false)
    var end = previewChars
    if (Character.isHighSurrogate(text[end - 1])) end -= 1
    return ThinkingPreview(text.substring(0, end).trimEnd() + "…", text.length - end, plain = true, truncated = true)
}
