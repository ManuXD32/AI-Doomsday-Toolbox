package com.example.llamadroid.service.filestation.http

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.InputStream

sealed class RangeResult {
    object Full : RangeResult()
    data class Partial(val start: Long, val end: Long) : RangeResult() {
        val length: Long get() = end - start + 1
    }
    object Unsatisfiable : RangeResult()
}

/** Single-range `Range` header parsing and conditional-request helpers (RFC 7233/7232). */
object ByteRange {
    fun parse(header: String?, size: Long): RangeResult {
        val h = header?.trim() ?: return RangeResult.Full
        if (!h.regionMatches(0, "bytes=", 0, 6, ignoreCase = true)) return RangeResult.Full
        val spec = h.substring(6).trim()
        if (spec.contains(',')) return RangeResult.Full
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.Unsatisfiable
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        if (size <= 0) return if (a.isEmpty() && b.isEmpty()) RangeResult.Unsatisfiable else RangeResult.Full
        if (a.isEmpty()) {
            val n = b.toLongOrNull() ?: return RangeResult.Unsatisfiable
            if (n <= 0) return RangeResult.Unsatisfiable
            return RangeResult.Partial(maxOf(0, size - n), size - 1)
        }
        val start = a.toLongOrNull() ?: return RangeResult.Unsatisfiable
        if (start < 0 || start >= size) return RangeResult.Unsatisfiable
        if (b.isEmpty()) return RangeResult.Partial(start, size - 1)
        val end = b.toLongOrNull() ?: return RangeResult.Unsatisfiable
        if (end < start) return RangeResult.Unsatisfiable
        return RangeResult.Partial(start, minOf(end, size - 1))
    }

    /** `If-None-Match` evaluation (weak comparison, `*`, comma lists). */
    fun noneMatchHit(ifNoneMatch: String?, etag: String): Boolean {
        val h = ifNoneMatch?.trim() ?: return false
        if (h == "*") return true
        val target = stripWeak(etag)
        return h.split(',').any { stripWeak(it.trim()) == target }
    }

    /** `If-Match` evaluation (strong comparison); `*` matches anything. */
    fun matchHit(ifMatch: String, etag: String): Boolean {
        val h = ifMatch.trim()
        if (h == "*") return true
        return h.split(',').any { it.trim() == etag }
    }

    /** `If-Range` only supports the entity-tag form; a date form never matches (full response). */
    fun ifRangeAllowsRange(ifRange: String?, etag: String): Boolean {
        val h = ifRange?.trim() ?: return true
        if (h.startsWith("W/")) return false
        return h == etag
    }

    private fun stripWeak(tag: String): String = tag.removePrefix("W/")
}

/** Builds 200/206/304/416 and HEAD responses for a seekable byte source. */
object RawResponder {
    fun respond(
        req: ApiRequest,
        size: Long,
        etag: String,
        mime: String,
        extraHeaders: Map<String, String>,
        openAt: (Long) -> InputStream,
    ): Response {
        fun decorate(r: Response): Response {
            r.addHeader("Accept-Ranges", "bytes")
            r.addHeader("ETag", etag)
            extraHeaders.forEach { (k, v) -> r.addHeader(k, v) }
            return r
        }
        if (ByteRange.noneMatchHit(req.header("if-none-match"), etag)) {
            return decorate(HttpResponses.empty(HttpStatus.NOT_MODIFIED))
        }
        var range = ByteRange.parse(req.header("range"), size)
        if (range is RangeResult.Partial && !ByteRange.ifRangeAllowsRange(req.header("if-range"), etag)) {
            range = RangeResult.Full
        }
        if (range is RangeResult.Unsatisfiable) {
            val r = HttpResponses.empty(HttpStatus.RANGE)
            r.addHeader("Content-Range", "bytes */$size")
            return decorate(r)
        }
        val start = (range as? RangeResult.Partial)?.start ?: 0L
        val length = (range as? RangeResult.Partial)?.length ?: size
        val status = if (range is RangeResult.Partial) HttpStatus.PARTIAL else HttpStatus.OK
        val r: Response
        if (req.isHead) {
            r = NanoHTTPD.newFixedLengthResponse(status, mime, java.io.ByteArrayInputStream(ByteArray(0)), 0)
            r.addHeader("Content-Length", length.toString())
        } else {
            val body = if (length == 0L) java.io.ByteArrayInputStream(ByteArray(0)) else
                ClosingLimitedStream(openAt(start), length)
            r = NanoHTTPD.newFixedLengthResponse(status, mime, body, length)
        }
        if (range is RangeResult.Partial) {
            r.addHeader("Content-Range", "bytes ${range.start}-${range.end}/$size")
        }
        return decorate(r)
    }
}

/** Limited view of a file stream that closes the underlying stream. */
class ClosingLimitedStream(private val source: InputStream, limit: Long) : InputStream() {
    private val limited = LimitedInputStream(source, limit)
    override fun read(): Int = limited.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = limited.read(b, off, len)
    override fun available(): Int = limited.available()
    override fun close() { source.close() }
}
