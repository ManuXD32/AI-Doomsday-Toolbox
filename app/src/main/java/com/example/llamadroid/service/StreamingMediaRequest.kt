package com.example.llamadroid.service

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.FilterOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Base64

/**
 * A JSON request body that base64-encodes media directly into the OkHttp sink.
 *
 * The envelope strings are intentionally supplied by the caller because the
 * llama-server and remote summary endpoints use different JSON shapes.  They
 * must include all static JSON fields and the opening/closing array or object
 * delimiters; each file is written as one JSON string value.  The media bytes
 * are read in bounded chunks and are never materialized as a byte array or a
 * Kotlin String.
 */
class StreamingBase64JsonRequestBody(
    private val files: List<File>,
    private val jsonPrefix: String,
    private val jsonSuffix: String,
    private val itemSeparator: String = ",",
    private val valuePrefix: String = "",
    private val mediaType: MediaType = JSON_MEDIA_TYPE
) : RequestBody() {
    init {
        require(files.isNotEmpty()) { "At least one media file is required" }
        require(jsonPrefix.isNotEmpty()) { "JSON prefix must not be empty" }
        require(files.all { it.isFile && it.canRead() }) { "Every media file must be readable" }
    }

    override fun contentType(): MediaType = mediaType

    /** Streaming bodies intentionally use chunked transfer encoding. */
    override fun contentLength(): Long = -1L

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(jsonPrefix)
        files.forEachIndexed { index, file ->
            if (index > 0) sink.writeUtf8(itemSeparator)
            sink.writeByte('"'.code)
            sink.writeUtf8(valuePrefix)
            writeBase64(file, sink.outputStream())
            sink.writeByte('"'.code)
        }
        sink.writeUtf8(jsonSuffix)
    }

    private fun writeBase64(file: File, destination: OutputStream) {
        // Base64.Encoder.close() writes padding and then closes its delegate.
        // The delegate below intentionally does not close OkHttp's sink.
        val nonClosing = NonClosingOutputStream(destination)
        Base64.getEncoder().wrap(nonClosing).use { encoded ->
            file.inputStream().use { input ->
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    encoded.write(buffer, 0, read)
                }
            }
        }
    }

    private class NonClosingOutputStream(delegate: OutputStream) : FilterOutputStream(delegate) {
        override fun close() {
            flush()
        }
    }

    companion object {
        private const val STREAM_BUFFER_SIZE = 32 * 1024
        val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
    }
}

/** One-file convenience wrapper for a remote video or frame request. */
fun streamingBase64JsonRequestBody(
    file: File,
    jsonPrefix: String,
    jsonSuffix: String,
    mediaType: MediaType = StreamingBase64JsonRequestBody.JSON_MEDIA_TYPE
): RequestBody = StreamingBase64JsonRequestBody(
    files = listOf(file),
    jsonPrefix = jsonPrefix,
    jsonSuffix = jsonSuffix,
    mediaType = mediaType
)

/** Array convenience wrapper used when a remote endpoint accepts sampled frames. */
fun streamingBase64JsonArrayRequestBody(
    files: List<File>,
    jsonPrefix: String,
    jsonSuffix: String,
    mediaType: MediaType = StreamingBase64JsonRequestBody.JSON_MEDIA_TYPE
): RequestBody = StreamingBase64JsonRequestBody(
    files = files,
    jsonPrefix = jsonPrefix,
    jsonSuffix = jsonSuffix,
    itemSeparator = ",",
    mediaType = mediaType
)

/**
 * Replaces ordered quoted [llamaVideoStreamMarker] values in an already serialized JSON
 * envelope with streamed base64 file values. The JSON envelope stays bounded in memory while
 * each image/video file is encoded directly into the OkHttp sink.
 */
internal fun buildStreamingJsonRequestBody(
    json: String,
    files: List<String>,
    valuePrefix: String = ""
): RequestBody {
    require(files.isNotEmpty()) { "At least one media file is required" }
    val markerTokens = files.indices.map { index ->
        "\"${llamaVideoStreamMarker(index)}\""
    }
    markerTokens.forEach { token ->
        require(json.indexOf(token) >= 0) { "Video stream marker is missing from request JSON" }
    }

    return object : RequestBody() {
        override fun contentType(): MediaType = StreamingBase64JsonRequestBody.JSON_MEDIA_TYPE

        override fun contentLength(): Long = -1L

        override fun writeTo(sink: BufferedSink) {
            var offset = 0
            markerTokens.forEachIndexed { index, token ->
                val markerStart = json.indexOf(token, offset)
                require(markerStart >= 0) { "Video stream marker order is invalid" }
                sink.writeUtf8(json.substring(offset, markerStart))
                StreamingBase64JsonRequestBody(
                    files = listOf(File(files[index])),
                    // The shared writer emits the JSON string quotes itself; whitespace keeps
                    // its non-empty envelope contract while remaining valid in this position.
                    jsonPrefix = " ",
                    jsonSuffix = " ",
                    valuePrefix = valuePrefix
                ).writeTo(sink)
                offset = markerStart + token.length
            }
            sink.writeUtf8(json.substring(offset))
        }
    }
}
