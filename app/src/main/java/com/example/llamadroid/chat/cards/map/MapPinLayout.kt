package com.example.llamadroid.chat.cards.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A pin to lay out: projected [x]/[y] (preview px) and the measured label box ([labelW] x [labelH]). */
data class PinInput(val x: Float, val y: Float, val labelW: Float, val labelH: Float)

/** Laid-out pin: final centre ([x], [y]), the label's top-left ([labelX], [labelY]) and whether the label is drawn. */
data class PinPlacement(val x: Float, val y: Float, val labelX: Float, val labelY: Float, val labelVisible: Boolean)

/**
 * Pure pin/label collision layout in preview pixels. Pins are laid out in order: a pin whose circle would overlap an
 * earlier pin is nudged to the first free spot on a ring around its position (it keeps its number). Labels sit under the
 * pin; a label is hidden when its box touches any other pin or an already shown label (the list under the map has names).
 */
object MapPinLayout {
    fun layout(
        pins: List<PinInput>,
        radius: Float,
        width: Float,
        height: Float,
        labelGap: Float,
        margin: Float = 4f,
    ): List<PinPlacement> {
        val centres = ArrayList<Pair<Float, Float>>(pins.size)
        val minDist = radius * 1.7f
        for (p in pins) {
            var best = p.x to p.y
            if (centres.any { hypot(it.first - best.first, it.second - best.second) < minDist }) {
                val step = radius * 1.8f
                var found: Pair<Float, Float>? = null
                loop@ for (ring in 1..3) {
                    for (k in 0 until 8) {
                        val a = k * PI / 4
                        val c = (p.x + (cos(a) * step * ring).toFloat()) to (p.y + (sin(a) * step * ring).toFloat())
                        if (centres.none { hypot(it.first - c.first, it.second - c.second) < minDist }) {
                            found = c
                            break@loop
                        }
                    }
                }
                best = found ?: best
            }
            centres += best
        }
        val shown = ArrayList<FloatArray>()
        return pins.mapIndexed { i, p ->
            val (cx, cy) = centres[i]
            val lx = (cx - p.labelW / 2f).coerceIn(margin, (width - p.labelW - margin).coerceAtLeast(margin))
            val ly = (cy + radius + labelGap).coerceAtMost((height - p.labelH - margin).coerceAtLeast(0f))
            val box = floatArrayOf(lx - margin / 2f, ly, lx + p.labelW + margin / 2f, ly + p.labelH)
            val hitsPin = centres.indices.any { j ->
                val (ox, oy) = centres[j]
                if (j == i) {
                    // Own pin: only when the label was pushed up by the bottom edge into its own circle.
                    boxHitsCircle(box, ox, oy, radius) && ly < cy + radius
                } else boxHitsCircle(box, ox, oy, radius)
            }
            val visible = !hitsPin && shown.none { overlaps(it, box) }
            if (visible) shown += box
            PinPlacement(cx, cy, lx, ly, visible)
        }
    }

    private fun overlaps(a: FloatArray, b: FloatArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    private fun boxHitsCircle(b: FloatArray, cx: Float, cy: Float, r: Float): Boolean {
        val nx = cx.coerceIn(b[0], b[2])
        val ny = cy.coerceIn(b[1], b[3])
        return hypot(cx - nx, cy - ny) < r
    }
}
