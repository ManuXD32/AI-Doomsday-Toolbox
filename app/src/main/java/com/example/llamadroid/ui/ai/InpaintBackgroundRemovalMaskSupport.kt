package com.example.llamadroid.ui.ai

import android.graphics.BitmapFactory
import java.io.File

/** Which side of a background-removal foreground mask becomes editable in inpainting. */
internal enum class InpaintAutoMaskPolarity {
    AUTO_SUBJECT,
    AUTO_BACKGROUND
}

/** A decoded foreground-alpha plane independent of Android bitmap lifetime. */
internal data class ForegroundMaskAlphaPlane(
    val width: Int,
    val height: Int,
    val alpha: ByteArray
) {
    init {
        require(width > 0 && height > 0)
        require(alpha.size == width * height)
    }
}

/**
 * Decodes a background-removal export. The documented `maskPath` export is an opaque
 * grayscale PNG, while a transparent foreground PNG can also be used as an alpha source.
 */
internal fun readForegroundMaskExport(maskFile: File): ForegroundMaskAlphaPlane {
    require(maskFile.isFile && maskFile.canRead()) { "Foreground mask export is missing or unreadable" }
    val bitmap = BitmapFactory.decodeFile(maskFile.absolutePath)
        ?: error("Unable to decode foreground mask export")
    try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ForegroundMaskAlphaPlane(
            width = bitmap.width,
            height = bitmap.height,
            alpha = foregroundAlphaFromArgb(pixels)
        )
    } finally {
        bitmap.recycle()
    }
}

/**
 * Uses grayscale intensity for the canonical opaque mask PNG and alpha for a transparent
 * foreground image. This keeps the ONNX export contract and direct transparent exports useful.
 */
internal fun foregroundAlphaFromArgb(pixels: IntArray): ByteArray = ByteArray(pixels.size) { index ->
    val color = pixels[index]
    val alpha = color ushr 24 and 0xff
    val red = color ushr 16 and 0xff
    val green = color ushr 8 and 0xff
    val blue = color and 0xff
    if (red == green && green == blue) red.toByte() else alpha.toByte()
}

/**
 * Produces the canonical inpaint raster for the target source canvas. The resize is allowed only
 * for proportional exports, then uses nearest-neighbor so the mask keeps its foreground polarity.
 */
internal fun foregroundMaskToInpaintRaster(
    foregroundMask: ForegroundMaskAlphaPlane,
    targetWidth: Int,
    targetHeight: Int,
    polarity: InpaintAutoMaskPolarity
): InpaintMaskRaster {
    require(targetWidth > 0 && targetHeight > 0) { "Inpaint target dimensions must be positive" }
    require(
        InpaintMaskRaster.compatibleAspectRatio(
            foregroundMask.width,
            foregroundMask.height,
            targetWidth,
            targetHeight
        )
    ) { "Foreground mask aspect ratio does not match the source canvas" }
    val subjectMask = InpaintMaskRaster.fromForegroundAlpha(
        width = foregroundMask.width,
        height = foregroundMask.height,
        alpha = foregroundMask.alpha,
        selectBackground = polarity == InpaintAutoMaskPolarity.AUTO_BACKGROUND
    )
    return if (foregroundMask.width == targetWidth && foregroundMask.height == targetHeight) {
        subjectMask
    } else {
        InpaintMaskRaster.resizeNearest(
            sourceWidth = subjectMask.width,
            sourceHeight = subjectMask.height,
            source = subjectMask.snapshot(),
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
    }
}
