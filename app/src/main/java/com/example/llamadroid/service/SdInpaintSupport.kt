package com.example.llamadroid.service

import android.graphics.BitmapFactory
import java.io.File

class SdInpaintConfigurationException(message: String) : IllegalArgumentException(message)

data class SdInpaintImageInspection(
    val width: Int,
    val height: Int,
    val hasEditablePixels: Boolean = true
)

private fun inspectSdInpaintImage(file: File, inspectEditablePixels: Boolean): SdInpaintImageInspection? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    if (!inspectEditablePixels) {
        return SdInpaintImageInspection(bounds.outWidth, bounds.outHeight)
    }

    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
    return try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val hasEditablePixels = pixels.any { pixel ->
            val alpha = pixel ushr 24 and 0xFF
            val red = pixel shr 16 and 0xFF
            val green = pixel shr 8 and 0xFF
            val blue = pixel and 0xFF
            alpha > 0 && ((red + green + blue) / 3) > 0
        }
        SdInpaintImageInspection(bitmap.width, bitmap.height, hasEditablePixels)
    } finally {
        bitmap.recycle()
    }
}

fun validateSdInpaintInputs(
    sourceImagePath: String?,
    maskImagePath: String?,
    strength: Float,
    width: Int,
    height: Int,
    imageInspector: (File, Boolean) -> SdInpaintImageInspection? = ::inspectSdInpaintImage
) {
    val sourceFile = sourceImagePath?.let(::File)
    if (sourceFile == null || !sourceFile.canRead()) {
        throw SdInpaintConfigurationException("Inpainting source image is missing or unreadable")
    }
    val maskFile = maskImagePath?.let(::File)
    if (maskFile == null || !maskFile.canRead()) {
        throw SdInpaintConfigurationException("Inpainting mask is missing or unreadable")
    }
    if (!strength.isFinite() || strength !in 0f..1f) {
        throw SdInpaintConfigurationException("Inpainting strength must be between 0 and 1")
    }
    if (width !in 64..4096 || height !in 64..4096 || width % 8 != 0 || height % 8 != 0) {
        throw SdInpaintConfigurationException("Inpainting size must be 64..4096 and divisible by 8")
    }
    val source = imageInspector(sourceFile, false)
        ?: throw SdInpaintConfigurationException("Inpainting source image could not be decoded")
    val mask = imageInspector(maskFile, true)
        ?: throw SdInpaintConfigurationException("Inpainting mask could not be decoded")
    if (source.width != mask.width || source.height != mask.height) {
        throw SdInpaintConfigurationException("Inpainting source and mask must have the same dimensions")
    }
    if (!mask.hasEditablePixels) {
        throw SdInpaintConfigurationException("Inpainting mask has no editable white pixels")
    }
}
