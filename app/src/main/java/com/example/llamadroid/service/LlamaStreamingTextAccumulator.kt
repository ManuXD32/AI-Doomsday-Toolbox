package com.example.llamadroid.service

/**
 * Bounded-allocation text accumulator for streamed llama responses.
 *
 * Native/chat backends deliver many small chunks. Keeping the response in a
 * [StringBuilder] avoids repeatedly copying the complete response. Thinking
 * tags are parsed incrementally; only a short suffix is held back so a tag
 * split across two chunks is still recognized.
 */
internal class LlamaStreamingTextAccumulator(
    private val parseThinkingTags: Boolean,
    initialContent: String = "",
    initialThinking: String = ""
) {
    private enum class Mode {
        SEARCHING,
        THINKING,
        VISIBLE
    }

    private val raw = StringBuilder(initialContent)
    private val visible = StringBuilder(initialContent)
    private val dedicatedThinking = StringBuilder(initialThinking)
    private val taggedThinking = StringBuilder()
    private val pending = StringBuilder()
    private var mode = if (parseThinkingTags) Mode.SEARCHING else Mode.VISIBLE

    init {
        if (parseThinkingTags && initialContent.isNotEmpty()) {
            // An existing continuation/assistant draft is already visible. It
            // must not be reparsed as a fresh thinking block.
            mode = Mode.VISIBLE
        }
    }

    fun appendContent(delta: String) {
        if (delta.isEmpty()) return
        raw.append(delta)
        if (!parseThinkingTags) {
            visible.append(delta)
            return
        }
        pending.append(delta)
        drainPending(flushAll = false)
    }

    fun appendDedicatedThinking(delta: String) {
        if (delta.isNotEmpty()) dedicatedThinking.append(delta)
    }

    fun reset(content: String = "", thinking: String = "") {
        raw.setLength(0)
        raw.append(content)
        visible.setLength(0)
        visible.append(content)
        dedicatedThinking.setLength(0)
        dedicatedThinking.append(thinking)
        taggedThinking.setLength(0)
        pending.setLength(0)
        mode = if (parseThinkingTags && content.isEmpty()) Mode.SEARCHING else Mode.VISIBLE
    }

    fun snapshot(includeRawContent: Boolean = false): Snapshot {
        val visibleText = StringBuilder(visible)
        val thinkingText = combinedThinking()
        if (parseThinkingTags) {
            when (mode) {
                Mode.SEARCHING, Mode.VISIBLE -> visibleText.append(pending)
                Mode.THINKING -> thinkingText.append(pending)
            }
        }
        return Snapshot(
            content = visibleText.toString().trim(),
            thinking = thinkingText.toString().trim(),
            rawContent = if (includeRawContent) raw.toString() else ""
        )
    }

    /** Returns the unparsed stream only when a tool round needs its exact visible prefix. */
    fun rawContent(): String = raw.toString()

    fun finish(): Snapshot {
        if (parseThinkingTags) drainPending(flushAll = true)
        return Snapshot(
            content = visible.toString().trim(),
            thinking = combinedThinking().toString().trim(),
            rawContent = raw.toString()
        )
    }

    private fun combinedThinking(): StringBuilder = StringBuilder(dedicatedThinking).apply {
        if (isNotEmpty() && taggedThinking.isNotEmpty()) append('\n')
        append(taggedThinking)
    }

    private fun drainPending(flushAll: Boolean) {
        while (pending.isNotEmpty()) {
            when (mode) {
                Mode.VISIBLE -> {
                    visible.append(pending)
                    pending.setLength(0)
                }
                Mode.SEARCHING -> {
                    val match = findFirstTag(pending, START_TAGS)
                    if (match != null) {
                        if (match.index > 0) visible.append(pending, 0, match.index)
                        pending.delete(0, match.index + match.tag.length)
                        mode = Mode.THINKING
                    } else {
                        val keep = if (flushAll) 0 else START_TAG_MAX_LENGTH - 1
                        val flush = (pending.length - keep).coerceAtLeast(0)
                        if (flush == 0) return
                        visible.append(pending, 0, flush)
                        pending.delete(0, flush)
                        if (!flushAll) return
                    }
                }
                Mode.THINKING -> {
                    val match = findFirstTag(pending, END_TAGS)
                    if (match != null) {
                        if (match.index > 0) taggedThinking.append(pending, 0, match.index)
                        pending.delete(0, match.index + match.tag.length)
                        mode = Mode.VISIBLE
                    } else {
                        val keep = if (flushAll) 0 else END_TAG_MAX_LENGTH - 1
                        val flush = (pending.length - keep).coerceAtLeast(0)
                        if (flush == 0) return
                        taggedThinking.append(pending, 0, flush)
                        pending.delete(0, flush)
                        if (!flushAll) return
                    }
                }
            }
        }
    }

    private data class TagMatch(val index: Int, val tag: String)

    private fun findFirstTag(value: CharSequence, tags: List<String>): TagMatch? {
        var best: TagMatch? = null
        for (tag in tags) {
            val index = value.indexOfIgnoreCase(tag)
            if (index >= 0 && (best == null || index < best!!.index)) {
                best = TagMatch(index, tag)
            }
        }
        return best
    }

    private fun CharSequence.indexOfIgnoreCase(needle: String): Int {
        if (needle.isEmpty()) return 0
        if (needle.length > length) return -1
        val lastStart = length - needle.length
        for (start in 0..lastStart) {
            if (regionMatches(start, needle, 0, needle.length, ignoreCase = true)) return start
        }
        return -1
    }

    internal data class Snapshot(
        val content: String,
        val thinking: String,
        val rawContent: String
    )

    private companion object {
        val START_TAGS = listOf("<think>", "<|think|>", "<thought>", "<Thought>", "<Think>")
        val END_TAGS = listOf("</think>", "</|think|>", "</thought>", "</Thought>", "</Think>")
        val START_TAG_MAX_LENGTH = START_TAGS.maxOf(String::length)
        val END_TAG_MAX_LENGTH = END_TAGS.maxOf(String::length)
    }
}
