package com.example.llamadroid.screenshots.harness

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Saved files of one shot. */
data class SavedShot(val png: File, val webp: File, val pngBytes: Int, val webpBytes: Int, val sha256: String)

/** Writes `<out>/<profile>/<locale>/<key>.png|.webp` and merges `manifest.json`. */
object ShotOutput {
    fun pngBytes(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encode failed" }
    }.toByteArray()

    @Suppress("DEPRECATION")
    fun webpBytes(bitmap: Bitmap, quality: Int = 80): ByteArray = ByteArrayOutputStream().also {
        check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, it)) { "WebP encode failed" }
    }.toByteArray()

    fun scaledToLongSide(bitmap: Bitmap, longSide: Int): Bitmap {
        val long = maxOf(bitmap.width, bitmap.height)
        if (long <= longSide) return bitmap
        val scale = longSide.toDouble() / long
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true
        )
    }

    fun save(
        outRoot: File, profile: ShotProfile, locale: ShotLocale, key: String, route: String,
        bitmap: Bitmap, substitute: Boolean = false, capture: String = "", millis: Long = 0
    ): SavedShot {
        val dir = File(outRoot, "${profile.key}/${locale.tag}").apply { mkdirs() }
        val png = pngBytes(bitmap)
        val webp = webpBytes(bitmap)
        val pngFile = File(dir, "$key.png").apply { writeBytes(png) }
        val webpFile = File(dir, "$key.webp").apply { writeBytes(webp) }
        val sha = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
        synchronized(this) {
            val manifestFile = File(dir, "manifest.json")
            val manifest = if (manifestFile.isFile) JSONObject(manifestFile.readText()) else JSONObject()
            manifest.put(key, JSONObject().apply {
                put("route", route)
                put("width", bitmap.width)
                put("height", bitmap.height)
                put("sha256", sha)
                put("substitute", substitute)
                put("capture", capture)
                put("pngBytes", png.size)
                put("webpBytes", webp.size)
                put("millis", millis)
            })
            manifestFile.writeText(manifest.toString(2))
        }
        return SavedShot(pngFile, webpFile, png.size, webp.size, sha)
    }
}
