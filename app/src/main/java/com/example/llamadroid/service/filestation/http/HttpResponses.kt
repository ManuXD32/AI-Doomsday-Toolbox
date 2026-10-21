package com.example.llamadroid.service.filestation.http

import com.example.llamadroid.service.filestation.volume.FsEntry
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Arbitrary HTTP status (NanoHTTPD's enum lacks e.g. 507). */
class HttpStatus(private val code: Int, private val text: String) : Response.IStatus {
    override fun getRequestStatus(): Int = code
    override fun getDescription(): String = "$code $text"

    companion object {
        val OK = HttpStatus(200, "OK")
        val ACCEPTED = HttpStatus(202, "Accepted")
        val PARTIAL = HttpStatus(206, "Partial Content")
        val NOT_MODIFIED = HttpStatus(304, "Not Modified")
        val BAD_REQUEST = HttpStatus(400, "Bad Request")
        val UNAUTHORIZED = HttpStatus(401, "Unauthorized")
        val FORBIDDEN = HttpStatus(403, "Forbidden")
        val NOT_FOUND = HttpStatus(404, "Not Found")
        val METHOD_NOT_ALLOWED = HttpStatus(405, "Method Not Allowed")
        val CONFLICT = HttpStatus(409, "Conflict")
        val LENGTH_REQUIRED = HttpStatus(411, "Length Required")
        val PRECONDITION_FAILED = HttpStatus(412, "Precondition Failed")
        val TOO_LARGE = HttpStatus(413, "Payload Too Large")
        val UNSUPPORTED = HttpStatus(415, "Unsupported Media Type")
        val RANGE = HttpStatus(416, "Range Not Satisfiable")
        val TOO_MANY = HttpStatus(429, "Too Many Requests")
        val INTERNAL = HttpStatus(500, "Internal Server Error")
        val NOT_IMPLEMENTED = HttpStatus(501, "Not Implemented")
        val BUSY = HttpStatus(503, "Service Unavailable")
        val NO_SPACE = HttpStatus(507, "Insufficient Storage")
    }
}

/** Thrown by handlers; the server turns it into the JSON error body of docs/file-station.md. */
class HttpError(
    val status: HttpStatus,
    val code: String,
    message: String,
    val extras: JsonObject = JsonObject(emptyMap()),
    val headers: Map<String, String> = emptyMap(),
) : Exception(message)

object HttpResponses {
    const val JSON = "application/json; charset=utf-8"

    /** Responses allowed to be gzipped (JSON and static assets only; never raw files or ranges). */
    private val gzipAllowed: MutableSet<Response> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun isGzipAllowed(r: Response): Boolean = gzipAllowed.contains(r)

    fun allowGzip(r: Response): Response {
        gzipAllowed.add(r)
        return r
    }

    fun json(element: JsonElement, status: HttpStatus = HttpStatus.OK): Response {
        val bytes = element.toString().toByteArray(Charsets.UTF_8)
        val r = NanoHTTPD.newFixedLengthResponse(
            status, JSON, ByteArrayInputStream(bytes), bytes.size.toLong(),
        )
        return allowGzip(r)
    }

    fun ok(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): Response =
        json(buildJsonObject {
            put("ok", true)
            build()
        })

    fun empty(status: HttpStatus): Response =
        NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, ByteArrayInputStream(ByteArray(0)), 0)

    fun error(status: HttpStatus, code: String, message: String, extras: JsonObject = JsonObject(emptyMap())): Response =
        json(
            buildJsonObject {
                put("error", buildJsonObject {
                    put("code", code)
                    put("message", message)
                })
                extras.forEach { (k, v) -> put(k, v) }
            },
            status,
        )

    fun fromHttpError(e: HttpError): Response {
        val r = error(e.status, e.code, e.message ?: e.code, e.extras)
        e.headers.forEach { (k, v) -> r.addHeader(k, v) }
        return r
    }

    fun fromFs(e: FsException): Response {
        val (status, code) = fsStatus(e.code)
        return error(status, code, e.message ?: code)
    }

    fun fsStatus(code: FsErrorCode): Pair<HttpStatus, String> = when (code) {
        FsErrorCode.NOT_FOUND -> HttpStatus.NOT_FOUND to "NOT_FOUND"
        FsErrorCode.EXISTS -> HttpStatus.CONFLICT to "EXISTS"
        FsErrorCode.READ_ONLY -> HttpStatus.FORBIDDEN to "READ_ONLY"
        FsErrorCode.INVALID_PATH -> HttpStatus.BAD_REQUEST to "INVALID_PATH"
        FsErrorCode.NO_SPACE -> HttpStatus.NO_SPACE to "NO_SPACE"
        FsErrorCode.UNSUPPORTED -> HttpStatus.UNSUPPORTED to "UNSUPPORTED"
        FsErrorCode.IO -> HttpStatus.INTERNAL to "IO"
    }

    fun entryJson(e: FsEntry, vol: String? = null): JsonObject = buildJsonObject {
        if (vol != null) put("vol", vol)
        put("name", e.name)
        put("path", e.path)
        put("isDir", e.isDir)
        put("size", e.size)
        put("mtime", e.mtime)
        put("mime", e.mime)
        put("etag", e.etag)
    }

    /** RFC 5987 / 6266 Content-Disposition with an ASCII fallback and a UTF-8 `filename*`. */
    fun contentDisposition(name: String, attachment: Boolean): String {
        val type = if (attachment) "attachment" else "inline"
        val ascii = buildString {
            name.forEach { c ->
                append(if (c.code in 0x20..0x7e && c != '"' && c != '\\' && c != '%') c else '_')
            }
        }
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "$type; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    /** HTML, SVG and XML are served sandboxed so they cannot script the file-station origin. */
    fun isActiveContent(mime: String): Boolean {
        val m = mime.lowercase().substringBefore(';').trim()
        return m == "text/html" || m == "application/xhtml+xml" || m == "image/svg+xml" ||
            m == "text/xml" || m == "application/xml"
    }

    fun str(p: JsonElement?): String? = (p as? JsonPrimitive)?.takeIf { it.isString }?.content
}
