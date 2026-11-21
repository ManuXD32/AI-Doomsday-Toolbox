package com.example.llamadroid.ui.training

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * Visible x-range of a chart as fractions of the full data range (0..1). Pure math so it can be unit-tested; the
 * gesture modifier below only feeds it.
 */
data class BranchyChartViewport(val lo: Float = 0f, val hi: Float = 1f) {
    val span: Float get() = hi - lo
    val isFull: Boolean get() = lo <= 0f && hi >= 1f

    /** Zooms by [factor] (>1 = zoom in) around [focus] (0..1 inside the visible range). Span is clamped to [MIN_SPAN]..1. */
    fun zoom(factor: Float, focus: Float = 0.5f): BranchyChartViewport {
        if (!factor.isFinite() || factor <= 0f) return this
        val f = focus.coerceIn(0f, 1f)
        val newSpan = (span / factor).coerceIn(MIN_SPAN, 1f)
        val anchor = lo + span * f
        return placed(anchor - newSpan * f, newSpan)
    }

    /** Pans by [dxFraction] of the visible span (positive = towards later x); clamped to the data range. */
    fun pan(dxFraction: Float): BranchyChartViewport =
        if (!dxFraction.isFinite()) this else placed(lo + dxFraction * span, span)

    /** Maps a 0..1 position inside the visible range to a fraction of the full range. */
    fun toFull(visibleFraction: Float): Float = lo + visibleFraction.coerceIn(0f, 1f) * span

    fun reset(): BranchyChartViewport = Full

    private fun placed(newLo: Float, newSpan: Float): BranchyChartViewport {
        val l = newLo.coerceIn(0f, 1f - newSpan)
        return BranchyChartViewport(l, l + newSpan)
    }

    companion object {
        const val MIN_SPAN = 0.05f
        val Full = BranchyChartViewport()
    }
}

/**
 * Pinch-zoom and drag-pan on x, double-tap to reset, single tap forwarded as a 0..1 fraction of the visible width.
 * [viewport] is read on every gesture so the caller keeps the state.
 */
fun Modifier.branchyChartGestures(
    key: Any?,
    viewport: () -> BranchyChartViewport,
    onViewport: (BranchyChartViewport) -> Unit,
    onTap: ((Float) -> Unit)? = null
): Modifier = composed {
    // The detectors restart only with [key]; the latest lambdas are read through these holders.
    val currentViewport by rememberUpdatedState(viewport)
    val currentOnViewport by rememberUpdatedState(onViewport)
    val currentOnTap by rememberUpdatedState(onTap)
    this
        .pointerInput(key) {
            detectTapGestures(
                onDoubleTap = { currentOnViewport(BranchyChartViewport.Full) },
                onTap = { offset ->
                    currentOnTap?.invoke((offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f))
                }
            )
        }
        .pointerInput(key) {
            // Two fingers always zoom/pan the chart; one finger pans only when zoomed in and moving sideways, so a
            // vertical drag on the chart still scrolls the page.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val fingers = event.changes.count { it.pressed }
                    val pan = event.calculatePan()
                    val v0 = currentViewport()
                    val handle = fingers >= 2 || (!v0.isFull && abs(pan.x) > abs(pan.y))
                    if (handle) {
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        var v = v0
                        val zoom = if (fingers >= 2) event.calculateZoom() else 1f
                        if (zoom != 1f) v = v.zoom(zoom, (event.calculateCentroid().x / w).coerceIn(0f, 1f))
                        if (pan.x != 0f) v = v.pan(-pan.x / w)
                        if (v != v0) currentOnViewport(v)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
}
