package com.example.llamadroid.worlds

import java.util.Base64

/** A file a world page asked the app to save (decoded, validated). */
class WorldPendingFile(val name: String, val mime: String, val bytes: ByteArray)

/**
 * Pure validation for `ADTHost.saveFile`: the page is untrusted model output, so only a small set of
 * extensions is accepted, names are reduced to a safe leaf name and the payload is size-capped.
 */
object WorldDownloads {
    const val MAX_BYTES = 50L * 1024 * 1024
    private const val MAX_NAME = 80
    private val allowedExtensions = setOf("glb", "gltf", "png", "html", "obj", "json")
    private val unsafeChars = Regex("""[^A-Za-z0-9._ \-]""")

    /** Returns a sanitized file name, or null when the extension is not allowed. */
    fun sanitizeName(raw: String): String? {
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val clean = unsafeChars.replace(leaf, "_").trim('.', ' ', '_').ifEmpty { return null }
        val ext = clean.substringAfterLast('.', "").lowercase()
        if (ext !in allowedExtensions) return null
        val stem = clean.dropLast(ext.length + 1).trim('.', ' ').ifEmpty { "world" }
        return stem.take(MAX_NAME) + "." + ext
    }

    /** Decodes [base64] after checking the size cap on the encoded length; null when invalid or too large. */
    fun decode(base64: String, maxBytes: Long = MAX_BYTES): ByteArray? {
        if (base64.length / 4L * 3L > maxBytes) return null
        return try {
            Base64.getDecoder().decode(base64).takeIf { it.size <= maxBytes }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Validates a request from the page; null when it must be rejected. */
    fun accept(name: String, mime: String, base64: String): WorldPendingFile? {
        val safe = sanitizeName(name) ?: return null
        val bytes = decode(base64) ?: return null
        return WorldPendingFile(safe, mime.take(100), bytes)
    }
}
