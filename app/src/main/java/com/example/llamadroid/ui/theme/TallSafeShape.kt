package com.example.llamadroid.ui.theme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * [shape], except that containers taller or wider than [MAX_PATH_OUTLINE_PX] get a plain rectangle outline.
 *
 * Compose hit-tests a clipped layer whose outline is a path (any [PixelCornerShape], and a rounded shape with
 * different corner radii) through a path intersection that loses float precision past ~8192 px, so every tap
 * below that line is dropped. Use it for containers that can grow very tall, such as chat bubbles holding many
 * interactive cards. Corners of such a tall container are rarely on screen together with its far end.
 */
class TallSafeShape(private val shape: Shape) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        if (maxOf(size.width, size.height) > MAX_PATH_OUTLINE_PX) {
            Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        } else {
            shape.createOutline(size, layoutDirection, density)
        }

    override fun equals(other: Any?): Boolean = other is TallSafeShape && other.shape == shape

    override fun hashCode(): Int = shape.hashCode()
}
