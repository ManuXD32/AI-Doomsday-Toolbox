package com.example.llamadroid.service

/**
 * Pure, bounded staging area between a chatty native child and Compose/runtime
 * projection. Callers append on any thread; the owner drains on a fixed cadence.
 */
internal class LlamaServerLogBuffer(
    private val maxTail: Int = DEFAULT_MAX_TAIL,
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private val maxBatch: Int = DEFAULT_MAX_BATCH
) {
    private var activeGeneration: Long? = null
    private var epoch = 0L
    private val pending = ArrayDeque<String>()
    private val tail = ArrayDeque<String>()
    private var repeatedLine: String? = null
    private var repeatedCount = 0
    private var droppedCount = 0

    fun reset(generation: Long) {
        activeGeneration = generation
        epoch++
        pending.clear()
        tail.clear()
        repeatedLine = null
        repeatedCount = 0
        droppedCount = 0
    }

    /** Returns false when a callback belongs to a launch that has already been superseded. */
    fun append(generation: Long, message: String): Boolean {
        if (activeGeneration == null) activeGeneration = generation
        if (generation != activeGeneration) return false
        return appendCurrent(message)
    }

    /** Appends diagnostic output that is not tied to a lifecycle callback. */
    fun appendCurrent(message: String): Boolean {
        if (activeGeneration == null) activeGeneration = 0L
        val line = message.trimEnd()
        if (line.isBlank()) return true
        if (repeatedLine == line) {
            repeatedCount++
            return true
        }
        materializeRepeatedLine()
        repeatedLine = line
        repeatedCount = 1
        return true
    }

    fun drain(): LlamaServerLogFlush? {
        materializeRepeatedLine()
        if (pending.isEmpty() && droppedCount == 0) return null

        val batch = ArrayList<String>(maxBatch)
        while (pending.isNotEmpty() && batch.size < maxBatch) {
            batch += pending.removeFirst()
        }
        if (droppedCount > 0 && batch.size < maxBatch) {
            batch += "[log buffer dropped $droppedCount line(s)]"
            droppedCount = 0
        }
        batch.forEach { line ->
            tail.addLast(line)
            while (tail.size > maxTail) tail.removeFirst()
        }
        return LlamaServerLogFlush(
            generation = activeGeneration ?: 0L,
            epoch = epoch,
            lines = batch,
            tail = tail.toList(),
            hasMore = pending.isNotEmpty() || repeatedCount > 0 || droppedCount > 0
        )
    }

    fun isCurrent(flush: LlamaServerLogFlush): Boolean =
        flush.generation == activeGeneration && flush.epoch == epoch

    fun hasPending(): Boolean = pending.isNotEmpty() || repeatedCount > 0 || droppedCount > 0

    private fun materializeRepeatedLine() {
        val line = repeatedLine ?: return
        val rendered = if (repeatedCount > 1) {
            "$line [repeated $repeatedCount times]"
        } else {
            line
        }
        if (pending.size < maxPending) {
            pending.addLast(rendered)
        } else {
            droppedCount += repeatedCount
        }
        repeatedLine = null
        repeatedCount = 0
    }

    companion object {
        const val DEFAULT_MAX_TAIL = 128
        const val DEFAULT_MAX_PENDING = 256
        const val DEFAULT_MAX_BATCH = 32
    }
}

internal data class LlamaServerLogFlush(
    val generation: Long,
    val epoch: Long,
    val lines: List<String>,
    val tail: List<String>,
    val hasMore: Boolean
)
