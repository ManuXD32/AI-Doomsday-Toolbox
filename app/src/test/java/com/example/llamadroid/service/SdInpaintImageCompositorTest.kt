package com.example.llamadroid.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SdInpaintImageCompositorTest {
    @Test
    fun `same-size composite preserves source outside mask and writes generated inside`() {
        val directory = createTemporaryDirectory()
        val sourceFile = File(directory, "source.png")
        val maskFile = File(directory, "mask.png")
        val outputFile = File(directory, "output.png")
        val sourcePixel = 0xff123456.toInt()
        val generatedPixel = 0xffabcdef.toInt()
        writeBitmap(sourceFile, width = 2, height = 1, pixels = intArrayOf(sourcePixel, sourcePixel))
        writeBitmap(maskFile, width = 2, height = 1, pixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt()))
        writeBitmap(outputFile, width = 2, height = 1, pixels = intArrayOf(generatedPixel, generatedPixel))

        try {
            SdInpaintImageCompositor.composite(
                sourcePath = sourceFile.absolutePath,
                maskPath = maskFile.absolutePath,
                outputFile = outputFile
            )

            val result = BitmapFactory.decodeFile(outputFile.absolutePath)
                ?: error("Composited output was unreadable")
            try {
                assertEquals(sourcePixel, result.getPixel(0, 0))
                assertEquals(generatedPixel, result.getPixel(1, 0))
            } finally {
                result.recycle()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeBitmap(file: File, width: Int, height: Int, pixels: IntArray) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            FileOutputStream(file).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun createTemporaryDirectory(): File {
        val directory = File.createTempFile("inpaint-compositor-test", "dir")
        check(directory.delete())
        check(directory.mkdirs())
        return directory
    }
}
