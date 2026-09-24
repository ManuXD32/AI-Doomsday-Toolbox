package com.example.llamadroid.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Replaces pixels outside the mask with the exact decoded source pixels. */
internal object SdInpaintImageCompositor {
    private const val MAX_COMPOSITE_PIXELS = 16_777_216L

    fun composite(sourcePath: String, maskPath: String, outputFile: File) {
        val sourceBounds = decodeBounds(sourcePath) ?: error("Source image is unreadable")
        val maskBounds = decodeBounds(maskPath) ?: error("Inpaint mask is unreadable")
        require(sourceBounds == maskBounds) { "Inpaint mask dimensions do not match the source" }
        val pixelCount = sourceBounds.first.toLong() * sourceBounds.second.toLong()
        require(pixelCount <= MAX_COMPOSITE_PIXELS) { "Image is too large to composite safely" }

        var source: Bitmap? = null
        var mask: Bitmap? = null
        var decodedGenerated: Bitmap? = null
        var scaledGenerated: Bitmap? = null
        var generated: Bitmap? = null
        var temporary: File? = null
        try {
            val sourceBitmap = BitmapFactory.decodeFile(sourcePath)
                ?.also { source = it }
                ?: error("Source image is unreadable")
            val maskBitmap = BitmapFactory.decodeFile(maskPath)
                ?.also { mask = it }
                ?: error("Inpaint mask is unreadable")
            val generatedOptions = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decodedBitmap = BitmapFactory.decodeFile(outputFile.absolutePath, generatedOptions)
                ?.also { decodedGenerated = it }
                ?: error("Generated image is unreadable")
            val scaledBitmap = if (
                decodedBitmap.width == sourceBitmap.width && decodedBitmap.height == sourceBitmap.height
            ) {
                decodedBitmap
            } else {
                decodedBitmap.scale(sourceBitmap.width, sourceBitmap.height, filter = true)
                    .also { if (it !== decodedBitmap) scaledGenerated = it }
            }
            val generatedBitmap = if (scaledBitmap.isMutable) {
                scaledBitmap
            } else {
                scaledBitmap.copy(Bitmap.Config.ARGB_8888, true)
                    ?.also { if (it !== scaledBitmap) scaledGenerated = scaledBitmap }
                    ?: error("Generated image could not be made mutable")
            }
            generated = generatedBitmap
            val temporaryFile = File.createTempFile("inpaint-", ".png", outputFile.parentFile)
                .also { temporary = it }

            val sourceRow = IntArray(sourceBitmap.width)
            val maskRow = IntArray(maskBitmap.width)
            val generatedRow = IntArray(generatedBitmap.width)
            val compositeRow = IntArray(sourceBitmap.width)
            for (y in 0 until sourceBitmap.height) {
                sourceBitmap.getPixels(sourceRow, 0, sourceBitmap.width, 0, y, sourceBitmap.width, 1)
                maskBitmap.getPixels(maskRow, 0, maskBitmap.width, 0, y, maskBitmap.width, 1)
                generatedBitmap.getPixels(generatedRow, 0, generatedBitmap.width, 0, y, generatedBitmap.width, 1)
                for (x in 0 until sourceBitmap.width) {
                    val maskArgb = maskRow[x]
                    val maskValue = (((maskArgb ushr 16 and 0xff) * 299) +
                        ((maskArgb ushr 8 and 0xff) * 587) +
                        ((maskArgb and 0xff) * 114)) / 1000
                    compositeRow[x] = compositeInpaintPixel(sourceRow[x], generatedRow[x], maskValue)
                }
                generatedBitmap.setPixels(compositeRow, 0, sourceBitmap.width, 0, y, sourceBitmap.width, 1)
            }
            FileOutputStream(temporaryFile).use { stream ->
                check(generatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Could not encode composited image"
                }
                stream.flush()
                stream.fd.sync()
            }
            replaceAtomically(temporaryFile, outputFile)
        } finally {
            temporary?.delete()
            source?.recycle()
            mask?.recycle()
            if (scaledGenerated !== generated && scaledGenerated !== decodedGenerated) {
                scaledGenerated?.recycle()
            }
            if (decodedGenerated !== generated) decodedGenerated?.recycle()
            generated?.recycle()
        }
    }

    private fun decodeBounds(path: String): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    private fun replaceAtomically(temporary: File, outputFile: File) {
        try {
            Files.move(
                temporary.toPath(),
                outputFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
