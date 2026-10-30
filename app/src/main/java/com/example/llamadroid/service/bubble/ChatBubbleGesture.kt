package com.example.llamadroid.service.bubble

import kotlin.math.abs

/**
 * Pure tap-vs-drag and edge-snap math of the floating chat bubble (no Android types, unit tested).
 *
 * Feed it raw pointer coordinates; once the pointer travels farther than [touchSlopPx] from the
 * down point the gesture becomes a drag and [windowX]/[windowY] follow the finger.
 */
class ChatBubbleGesture(private val touchSlopPx: Float) {
    enum class Result { TAP, DRAG_END, NONE }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startWindowX = 0
    private var startWindowY = 0
    private var active = false

    var dragging = false
        private set
    var windowX = 0
        private set
    var windowY = 0
        private set

    fun onDown(rawX: Float, rawY: Float, currentWindowX: Int, currentWindowY: Int) {
        downRawX = rawX
        downRawY = rawY
        startWindowX = currentWindowX
        startWindowY = currentWindowY
        windowX = currentWindowX
        windowY = currentWindowY
        dragging = false
        active = true
    }

    /** Returns true while the gesture is a drag (the window position has been updated). */
    fun onMove(rawX: Float, rawY: Float): Boolean {
        if (!active) return false
        val dx = rawX - downRawX
        val dy = rawY - downRawY
        if (!dragging && abs(dx) <= touchSlopPx && abs(dy) <= touchSlopPx) return false
        dragging = true
        windowX = startWindowX + dx.toInt()
        windowY = startWindowY + dy.toInt()
        return true
    }

    fun onUp(): Result {
        if (!active) return Result.NONE
        active = false
        val wasDragging = dragging
        dragging = false
        return if (wasDragging) Result.DRAG_END else Result.TAP
    }

    fun cancel() {
        active = false
        dragging = false
    }

    companion object {
        /** Keeps a [size] square fully inside a [screenW] x [screenH] area. */
        fun clampX(x: Int, size: Int, screenW: Int): Int = x.coerceIn(0, (screenW - size).coerceAtLeast(0))

        fun clampY(y: Int, size: Int, screenH: Int): Int = y.coerceIn(0, (screenH - size).coerceAtLeast(0))

        /** Snaps to the horizontal edge nearest to the bubble centre, [marginPx] away from it. */
        fun snapX(x: Int, size: Int, screenW: Int, marginPx: Int = 0): Int {
            val left = marginPx.coerceAtMost((screenW - size).coerceAtLeast(0))
            val right = (screenW - size - marginPx).coerceAtLeast(0)
            return if (x + size / 2 < screenW / 2) left else right
        }
    }
}
