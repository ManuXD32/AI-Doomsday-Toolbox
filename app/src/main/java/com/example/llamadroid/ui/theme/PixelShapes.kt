package com.example.llamadroid.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Shapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Rectangle whose corners are cut as an 8-bit staircase of [steps] equal steps. Each corner's cut
 * comes from its [CornerSize], so Material helpers such as `top()` (bottom sheets) can square off
 * individual corners. Corner sizes are clamped by [CornerBasedShape] to fit the shape.
 */
class PixelCornerShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
    private val steps: Int = 3
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    /** Uniform staircase: [steps] steps of [step] each on every corner. */
    constructor(step: Dp = 3.dp, steps: Int = 3) : this(
        CornerSize(step * steps), CornerSize(step * steps), CornerSize(step * steps), CornerSize(step * steps), steps
    )

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize
    ): CornerBasedShape = PixelCornerShape(topStart, topEnd, bottomEnd, bottomStart, steps)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection
    ): Outline {
        val ltr = layoutDirection == LayoutDirection.Ltr
        val topLeft = if (ltr) topStart else topEnd
        val topRight = if (ltr) topEnd else topStart
        val bottomRight = if (ltr) bottomEnd else bottomStart
        val bottomLeft = if (ltr) bottomStart else bottomEnd
        val w = size.width
        val h = size.height
        if (topLeft + topRight + bottomRight + bottomLeft <= 0f) {
            return Outline.Rectangle(Rect(0f, 0f, w, h))
        }
        val count = steps.coerceAtLeast(1)
        val path = Path()
        // Walk clockwise from the left edge; each corner is a staircase of `count` steps.
        var s = topLeft / count
        path.moveTo(0f, topLeft)
        for (i in 0 until count) {
            path.lineTo(s * i, topLeft - s * (i + 1))
            path.lineTo(s * (i + 1), topLeft - s * (i + 1))
        }
        s = topRight / count
        path.lineTo(w - topRight, 0f)
        for (i in 0 until count) {
            path.lineTo(w - topRight + s * (i + 1), s * i)
            path.lineTo(w - topRight + s * (i + 1), s * (i + 1))
        }
        s = bottomRight / count
        path.lineTo(w, h - bottomRight)
        for (i in 0 until count) {
            path.lineTo(w - s * i, h - bottomRight + s * (i + 1))
            path.lineTo(w - s * (i + 1), h - bottomRight + s * (i + 1))
        }
        s = bottomLeft / count
        path.lineTo(bottomLeft, h)
        for (i in 0 until count) {
            path.lineTo(bottomLeft - s * (i + 1), h - s * i)
            path.lineTo(bottomLeft - s * (i + 1), h - s * (i + 1))
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** Material shape scale used while the pixel theme is active. */
val PixelShapes = Shapes(
    extraSmall = PixelCornerShape(2.dp, 2),
    small = PixelCornerShape(2.dp, 3),
    medium = PixelCornerShape(3.dp, 3),
    large = PixelCornerShape(3.dp, 3),
    extraLarge = PixelCornerShape(4.dp, 3)
)

/** Chunky outline that follows the stepped corners of [PixelCornerShape]. */
fun Modifier.pixelBorder(
    width: Dp = 2.dp,
    color: Color,
    step: Dp = 3.dp
): Modifier = border(BorderStroke(width, color), PixelCornerShape(step))
