package com.example.llamadroid.ui.ai

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Mutable, platform-independent grayscale inpaint mask.
 *
 * Values are unsigned bytes: 0 keeps the source pixel and 255 regenerates it.
 * Keeping the editing math independent of android.graphics makes mask behavior
 * deterministic and cheap to cover with local unit tests.
 */
internal class InpaintMaskRaster private constructor(
    val width: Int,
    val height: Int,
    private val pixels: ByteArray
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }

    fun snapshot(): ByteArray = pixels.copyOf()

    fun restore(snapshot: ByteArray) {
        require(snapshot.size == pixels.size)
        snapshot.copyInto(pixels)
    }

    fun valueAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return pixels[y * width + x].toInt() and 0xff
    }

    fun isEmpty(): Boolean = pixels.none { (it.toInt() and 0xff) > EMPTY_THRESHOLD }

    fun isFull(): Boolean = pixels.all { (it.toInt() and 0xff) >= FULL_THRESHOLD }

    fun clear() = pixels.fill(0)

    fun invert() {
        pixels.indices.forEach { index ->
            pixels[index] = (255 - (pixels[index].toInt() and 0xff)).toByte()
        }
    }

    /** Paints one circular dab. Softness is 0 for a hard edge and 1 for full falloff. */
    fun paintCircle(
        centerX: Float,
        centerY: Float,
        radius: Float,
        softness: Float,
        erase: Boolean
    ) {
        if (!centerX.isFinite() || !centerY.isFinite() || !radius.isFinite() || radius <= 0f) return
        val safeSoftness = softness.coerceIn(0f, 1f)
        val innerRadius = radius * (1f - safeSoftness)
        val startX = floor(centerX - radius).toInt().coerceAtLeast(0)
        val endX = ceil(centerX + radius).toInt().coerceAtMost(width - 1)
        val startY = floor(centerY - radius).toInt().coerceAtLeast(0)
        val endY = ceil(centerY + radius).toInt().coerceAtMost(height - 1)

        for (y in startY..endY) {
            for (x in startX..endX) {
                val distance = hypot((x + 0.5f - centerX).toDouble(), (y + 0.5f - centerY).toDouble()).toFloat()
                if (distance > radius) continue
                val coverage = when {
                    safeSoftness <= 0f || distance <= innerRadius -> 1f
                    else -> ((radius - distance) / max(radius - innerRadius, 0.0001f)).coerceIn(0f, 1f)
                }
                val amount = (coverage * 255f).toInt().coerceIn(0, 255)
                val index = y * width + x
                val current = pixels[index].toInt() and 0xff
                val updated = if (erase) min(current, 255 - amount) else max(current, amount)
                pixels[index] = updated.toByte()
            }
        }
    }

    /** Paints a continuous stroke, interpolating dabs so fast gestures do not leave gaps. */
    fun paintLine(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        radius: Float,
        softness: Float,
        erase: Boolean
    ) {
        val distance = hypot((toX - fromX).toDouble(), (toY - fromY).toDouble()).toFloat()
        val spacing = max(radius * 0.35f, 1f)
        val steps = max(1, ceil(distance / spacing).toInt())
        for (step in 0..steps) {
            val fraction = step.toFloat() / steps.toFloat()
            paintCircle(
                centerX = fromX + (toX - fromX) * fraction,
                centerY = fromY + (toY - fromY) * fraction,
                radius = radius,
                softness = softness,
                erase = erase
            )
        }
    }

    /** Fills a hard-edged rectangle. Shape tools use the same mutable mask as brushes. */
    fun paintRectangle(left: Float, top: Float, right: Float, bottom: Float, erase: Boolean) {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return
        val startX = floor(min(left, right)).toInt().coerceIn(0, width - 1)
        val endX = ceil(max(left, right)).toInt().coerceIn(0, width)
        val startY = floor(min(top, bottom)).toInt().coerceIn(0, height - 1)
        val endY = ceil(max(top, bottom)).toInt().coerceIn(0, height)
        if (endX <= startX || endY <= startY) return
        val value = if (erase) 0 else 255.toByte()
        for (y in startY until endY) {
            for (x in startX until endX) pixels[y * width + x] = value
        }
    }

    /** Fills a hard-edged ellipse bounded by the supplied rectangle. */
    fun paintEllipse(left: Float, top: Float, right: Float, bottom: Float, erase: Boolean) {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return
        val normalizedLeft = min(left, right)
        val normalizedRight = max(left, right)
        val normalizedTop = min(top, bottom)
        val normalizedBottom = max(top, bottom)
        val radiusX = (normalizedRight - normalizedLeft) / 2f
        val radiusY = (normalizedBottom - normalizedTop) / 2f
        if (radiusX <= 0f || radiusY <= 0f) return
        val centerX = normalizedLeft + radiusX
        val centerY = normalizedTop + radiusY
        val startX = floor(normalizedLeft).toInt().coerceIn(0, width - 1)
        val endX = ceil(normalizedRight).toInt().coerceIn(0, width)
        val startY = floor(normalizedTop).toInt().coerceIn(0, height - 1)
        val endY = ceil(normalizedBottom).toInt().coerceIn(0, height)
        val value = if (erase) 0 else 255.toByte()
        for (y in startY until endY) {
            for (x in startX until endX) {
                val dx = (x + 0.5f - centerX) / radiusX
                val dy = (y + 0.5f - centerY) / radiusY
                if (dx * dx + dy * dy <= 1f) pixels[y * width + x] = value
            }
        }
    }

    /** ARGB overlay pixels used by the editor: masked areas are translucent red. */
    fun toOverlayArgb(maxAlpha: Int = 150): IntArray {
        val safeMaxAlpha = maxAlpha.coerceIn(0, 255)
        return IntArray(pixels.size) { index ->
            val value = pixels[index].toInt() and 0xff
            val alpha = value * safeMaxAlpha / 255
            (alpha shl 24) or 0x00ff3b30
        }
    }

    /** Opaque grayscale pixels suitable for a PNG passed to stable-diffusion.cpp. */
    fun toOpaqueGrayscaleArgb(): IntArray = IntArray(pixels.size) { index ->
        val value = pixels[index].toInt() and 0xff
        0xff000000.toInt() or (value shl 16) or (value shl 8) or value
    }

    companion object {
        private const val EMPTY_THRESHOLD = 2
        private const val FULL_THRESHOLD = 252

        fun empty(width: Int, height: Int): InpaintMaskRaster =
            InpaintMaskRaster(width, height, ByteArray(width * height))

        fun fromBytes(width: Int, height: Int, pixels: ByteArray): InpaintMaskRaster =
            InpaintMaskRaster(width, height, pixels.copyOf())

        /** Converts a background-removal alpha plane into an inpaint mask. */
        fun fromForegroundAlpha(
            width: Int,
            height: Int,
            alpha: ByteArray,
            selectBackground: Boolean
        ): InpaintMaskRaster {
            require(alpha.size == width * height)
            val values = ByteArray(alpha.size) { index ->
                val foreground = alpha[index].toInt() and 0xff
                (if (selectBackground) 255 - foreground else foreground).toByte()
            }
            return InpaintMaskRaster(width, height, values)
        }

        /** Nearest-neighbor scaling preserves hard imported mask edges. */
        fun resizeNearest(
            sourceWidth: Int,
            sourceHeight: Int,
            source: ByteArray,
            targetWidth: Int,
            targetHeight: Int
        ): InpaintMaskRaster {
            require(sourceWidth > 0 && sourceHeight > 0)
            require(targetWidth > 0 && targetHeight > 0)
            require(source.size == sourceWidth * sourceHeight)
            val output = ByteArray(targetWidth * targetHeight)
            for (y in 0 until targetHeight) {
                val sourceY = min(sourceHeight - 1, y * sourceHeight / targetHeight)
                for (x in 0 until targetWidth) {
                    val sourceX = min(sourceWidth - 1, x * sourceWidth / targetWidth)
                    output[y * targetWidth + x] = source[sourceY * sourceWidth + sourceX]
                }
            }
            return InpaintMaskRaster(targetWidth, targetHeight, output)
        }

        fun compatibleAspectRatio(
            sourceWidth: Int,
            sourceHeight: Int,
            targetWidth: Int,
            targetHeight: Int,
            tolerance: Float = 0.01f
        ): Boolean {
            if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return false
            val sourceRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
            val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
            return kotlin.math.abs(sourceRatio - targetRatio) <= tolerance
        }
    }
}
