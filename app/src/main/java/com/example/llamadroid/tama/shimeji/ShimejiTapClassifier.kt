package com.example.llamadroid.tama.shimeji

import kotlin.math.abs

enum class ShimejiTapEvent { SINGLE, DOUBLE, TRIPLE }

/**
 * Turns raw touch events into tap groups. A group closes when no new tap starts within
 * [tapWindowMs] of the last tap release; the third tap emits [ShimejiTapEvent.TRIPLE] at once.
 * Moving farther than [touchSlopPx] makes the gesture a drag (any pending taps are discarded).
 */
class ShimejiTapClassifier(
    private val touchSlopPx: Float,
    private val tapWindowMs: Long = TAP_WINDOW_MS
) {
    private var downX = 0f
    private var downY = 0f
    private var isDown = false
    private var dragging = false
    private var tapCount = 0
    private var lastTapUpMs = 0L

    /** Returns an expired pending group (if the window closed before this touch), else null. */
    fun onDown(x: Float, y: Float, timeMs: Long): ShimejiTapEvent? {
        val expired = flush(timeMs)
        downX = x
        downY = y
        isDown = true
        dragging = false
        return expired
    }

    /** Returns true while the current gesture is a drag. */
    fun onMove(x: Float, y: Float, @Suppress("UNUSED_PARAMETER") timeMs: Long): Boolean {
        if (!isDown) return false
        if (!dragging && (abs(x - downX) > touchSlopPx || abs(y - downY) > touchSlopPx)) {
            dragging = true
            tapCount = 0
        }
        return dragging
    }

    /** Returns TRIPLE immediately on the third tap; null otherwise (drag, or group still open). */
    fun onUp(x: Float, y: Float, timeMs: Long): ShimejiTapEvent? {
        if (!isDown) return null
        onMove(x, y, timeMs)
        isDown = false
        if (dragging) {
            dragging = false
            tapCount = 0
            return null
        }
        tapCount++
        lastTapUpMs = timeMs
        if (tapCount >= 3) {
            tapCount = 0
            return ShimejiTapEvent.TRIPLE
        }
        return null
    }

    /** Deadline (same clock as the events) at which [flush] will emit, or null if nothing is pending. */
    fun pending(): Long? = if (tapCount > 0 && !isDown) lastTapUpMs + tapWindowMs else null

    /** Emits the open group once its deadline has passed. */
    fun flush(nowMs: Long): ShimejiTapEvent? {
        val deadline = pending() ?: return null
        if (nowMs < deadline) return null
        val event = if (tapCount == 1) ShimejiTapEvent.SINGLE else ShimejiTapEvent.DOUBLE
        tapCount = 0
        return event
    }

    fun reset() {
        isDown = false
        dragging = false
        tapCount = 0
    }

    companion object {
        const val TAP_WINDOW_MS = 350L
    }
}
