package com.example.llamadroid.service

/**
 * Small in-memory handoff between a native output callback and the durable session log.
 * Native callbacks never perform file I/O; the runtime drains this queue periodically.
 */
internal class LlamaServerSessionLogBatcher(
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private val maxBatch: Int = DEFAULT_MAX_BATCH
) {
    private val pending = ArrayDeque<String>()
    private var droppedLines = 0

    init {
        require(maxPending > 0) { "maxPending must be positive" }
        require(maxBatch > 0) { "maxBatch must be positive" }
    }

    fun append(line: String) {
        val clean = line
            .replace("\r", "")
            .replace("\u0000", "")
            .take(MAX_LINE_CHARS)
        if (pending.size >= maxPending) {
            pending.removeFirst()
            droppedLines++
        }
        pending.addLast(clean)
    }

    fun drain(): List<String> {
        if (pending.isEmpty() && droppedLines == 0) return emptyList()
        val result = ArrayList<String>(maxBatch)
        if (droppedLines > 0) {
            result += "[log buffer dropped $droppedLines line(s)]"
            droppedLines = 0
        }
        while (pending.isNotEmpty() && result.size < maxBatch) {
            result += pending.removeFirst()
        }
        return result
    }

    fun hasPending(): Boolean = pending.isNotEmpty() || droppedLines > 0

    companion object {
        const val DEFAULT_MAX_PENDING = 512
        const val DEFAULT_MAX_BATCH = 64
        private const val MAX_LINE_CHARS = 16 * 1024
    }
}
