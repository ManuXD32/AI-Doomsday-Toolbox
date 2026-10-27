package com.example.llamadroid.tama.shimeji

/**
 * Screen facts the overlay depends on. Display callbacks also fire for refresh-rate and other
 * changes that leave these values untouched, so the service only reacts when this value differs.
 */
data class ShimejiDisplayGeometry(
    val fullWidth: Int,
    val fullHeight: Int,
    val insetLeft: Int,
    val insetTop: Int,
    val insetRight: Int,
    val insetBottom: Int,
    val rotation: Int
) {
    companion object {
        /** True when the layout really changed (or nothing was cached yet). */
        fun hasChanged(previous: ShimejiDisplayGeometry?, current: ShimejiDisplayGeometry): Boolean =
            previous == null || previous != current
    }
}

/** Where the care menu sits relative to the pet. Pure so it can be tested. */
data class ShimejiPanelPlacement(
    val below: Boolean,
    val widthPx: Int,
    /** Left edge in screen px. */
    val x: Int,
    /** Distance from the top edge when [below], from the bottom edge otherwise. */
    val y: Int,
    /** Maximum panel height; the content scrolls beyond it. */
    val capPx: Int
) {
    companion object {
        /**
         * [anchorTop]/[anchorBottom]: pet span in screen px. The menu opens below the pet when the pet is
         * in the upper half, above it otherwise, and is always clamped inside the screen.
         */
        fun compute(
            anchorCenterX: Int,
            anchorTop: Int,
            anchorBottom: Int,
            screenW: Int,
            screenH: Int,
            gapPx: Int,
            preferredWidthPx: Int,
            minCapPx: Int
        ): ShimejiPanelPlacement {
            val top = anchorTop.coerceIn(0, screenH)
            val bottom = anchorBottom.coerceIn(top, screenH)
            val below = (top + bottom) / 2 < screenH / 2
            val room = if (below) screenH - bottom - gapPx else top - gapPx
            val capPx = maxOf(room, minCapPx).coerceAtMost((screenH * 0.7f).toInt()).coerceAtMost(screenH)
            val widthPx = minOf(preferredWidthPx, screenW - 2 * gapPx).coerceAtLeast(1)
            val x = (anchorCenterX - widthPx / 2).coerceIn(0, maxOf(0, screenW - widthPx))
            val rawY = if (below) bottom + gapPx else screenH - top + gapPx
            val y = rawY.coerceIn(0, maxOf(0, screenH - capPx))
            return ShimejiPanelPlacement(below, widthPx, x, y, capPx)
        }
    }
}
