package com.example.llamadroid.harness

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** Decode the authenticated index before bootstrap injection, regardless of HTTP framing. */
internal fun readHarnessWebIndexBody(
    input: InputStream,
    contentLength: Long?,
    chunked: Boolean,
    maxBytes: Int,
): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    fun readCount(count: Long) {
        require(count >= 0 && count <= maxBytes - output.size()) { "HARNESS_WEBUI_INDEX_TOO_LARGE" }
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) throw EOFException("HARNESS_WEBUI_INDEX_TRUNCATED")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
    fun line(): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val value = input.read()
            if (value < 0) throw EOFException("HARNESS_WEBUI_INDEX_TRUNCATED")
            bytes.write(value)
            if (value == 10) {
                val raw = bytes.toByteArray()
                require(raw.size >= 2 && raw[raw.size - 2] == 13.toByte()) { "HARNESS_WEBUI_INDEX_INVALID" }
                return raw.toString(Charsets.ISO_8859_1).dropLast(2)
            }
        }
        error("HARNESS_WEBUI_INDEX_INVALID")
    }
    when {
        chunked -> {
            var chunks = 0
            while (true) {
                require(++chunks <= maxBytes + 1) { "HARNESS_WEBUI_INDEX_INVALID" }
                val size = line().substringBefore(';').trim().toLongOrNull(16)
                    ?: error("HARNESS_WEBUI_INDEX_INVALID")
                if (size == 0L) {
                    var trailerBytes = 0
                    while (true) {
                        val trailer = line()
                        trailerBytes += trailer.length + 2
                        require(trailerBytes <= 8192) { "HARNESS_WEBUI_INDEX_INVALID" }
                        if (trailer.isEmpty()) break
                    }
                    break
                }
                readCount(size)
                require(line().isEmpty()) { "HARNESS_WEBUI_INDEX_INVALID" }
            }
        }
        contentLength != null -> readCount(contentLength)
        else -> {
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(read <= maxBytes - output.size()) { "HARNESS_WEBUI_INDEX_TOO_LARGE" }
                output.write(buffer, 0, read)
            }
        }
    }
    return output.toByteArray()
}
