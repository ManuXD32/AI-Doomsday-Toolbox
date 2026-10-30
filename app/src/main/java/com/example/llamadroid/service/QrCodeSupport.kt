package com.example.llamadroid.service

import androidx.core.graphics.createBitmap
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream

/** Shared QR rendering for the local servers (AI tool server and File Station). */
object QrCodeSupport {
    /** Square black-on-white QR bitmap of [size] pixels. */
    fun qrBitmap(content: String, size: Int): Bitmap {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** PNG bytes of [qrBitmap]. */
    fun qrPng(content: String, size: Int): ByteArray {
        val bitmap = qrBitmap(content, size)
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
