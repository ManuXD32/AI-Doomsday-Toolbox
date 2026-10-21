package com.example.llamadroid.service.filestation.http

import com.example.llamadroid.service.filestation.volume.FsPaths
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** One HTTP request as seen by handlers. Bodies are never parsed by NanoHTTPD (no parseBody). */
class ApiRequest(
    private val session: NanoHTTPD.IHTTPSession,
    val deps: FileStationDeps,
) {
    val method: NanoHTTPD.Method = session.method
    /** Already percent-decoded once by NanoHTTPD; do not decode again. */
    val uri: String = session.uri ?: "/"
    val isHead: Boolean get() = method == NanoHTTPD.Method.HEAD
    val remoteIp: String = session.remoteIpAddress ?: "unknown"

    /** True once any body bytes were read; unread bodies force `Connection: close`. */
    @Volatile var bodyTouched = false
        private set

    fun header(name: String): String? = session.headers[name.lowercase()]

    fun param(name: String): String? = session.parameters[name]?.firstOrNull()

    fun params(name: String): List<String> = session.parameters[name].orEmpty()

    fun requireParam(name: String): String =
        param(name) ?: throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "missing parameter: $name")

    fun longParam(name: String, default: Long): Long {
        val raw = param(name) ?: return default
        return raw.toLongOrNull() ?: throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "bad number: $name")
    }

    fun boolParam(name: String): Boolean = param(name)?.let { it == "1" || it.equals("true", true) } ?: false

    /** Normalized volume-relative path from a query parameter (already decoded). */
    fun pathParam(name: String): String = FsPaths.normalize(param(name).orEmpty())

    val contentLength: Long?
        get() = header("content-length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    /** Raw body stream limited to exactly Content-Length bytes (<= [max]); 411/413 otherwise. */
    fun bodyStream(max: Long): InputStream {
        val len = contentLength ?: throw HttpError(HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED", "Content-Length required")
        if (len > max) throw HttpError(HttpStatus.TOO_LARGE, "TOO_LARGE", "body exceeds $max bytes")
        bodyTouched = true
        return LimitedInputStream(session.inputStream, len)
    }

    /** Streams the body to [out] with a 64 KiB buffer; returns the byte count. */
    fun copyBodyTo(out: OutputStream, max: Long): Long {
        val input = bodyStream(max)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        if (total != (contentLength ?: total)) {
            throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "body truncated")
        }
        return total
    }

    fun readBodyBytes(max: Long): ByteArray {
        val bos = ByteArrayOutputStream()
        copyBodyTo(bos, max)
        return bos.toByteArray()
    }

    /** JSON object body (1 MiB cap). Missing/empty body yields an empty object. */
    fun jsonBody(): JsonObject {
        val len = contentLength
        if (len == null || len == 0L) {
            if (len == null && header("transfer-encoding") != null) {
                throw HttpError(HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED", "Content-Length required")
            }
            return JsonObject(emptyMap())
        }
        val bytes = readBodyBytes(MAX_JSON_BODY)
        val element = try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "invalid JSON")
        }
        return element as? JsonObject
            ?: throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "JSON object expected")
    }

    companion object {
        const val MAX_JSON_BODY = 1L shl 20
        const val MAX_TEXT_BODY = 32L shl 20
    }
}

/** Reads at most [limit] bytes from [source] and never reads past them (keep-alive safe). */
class LimitedInputStream(private val source: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = source.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun available(): Int = minOf(source.available().toLong(), remaining).toInt()
    override fun close() { /* the socket stream is owned by NanoHTTPD */ }
}

// JSON helpers shared by the API modules.
fun JsonObject.string(name: String): String? = HttpResponses.str(this[name])

fun JsonObject.requireString(name: String): String =
    string(name) ?: throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "missing field: $name")

fun JsonObject.bool(name: String, default: Boolean = false): Boolean =
    (this[name] as? JsonPrimitive)?.booleanOrNull ?: default

fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

fun JsonObject.array(name: String): JsonArray =
    this[name] as? JsonArray ?: throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "missing array: $name")

fun JsonElement.asObject(): JsonObject =
    this as? JsonObject ?: throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "object expected")
