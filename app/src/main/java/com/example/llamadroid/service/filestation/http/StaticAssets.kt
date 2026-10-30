package com.example.llamadroid.service.filestation.http

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream

/** Serves the bundled web UI from an [AssetSource]; bytes are streamed, never held whole. */
class StaticAssets(private val assets: AssetSource) {
    private val topLevel = setOf("index.html", "editor.html")
    private val dirs = listOf("css/", "js/", "i18n/", "vendor/", "icons/")

    fun serve(req: ApiRequest): Response {
        val path = resolve(req.uri) ?: return notFound()
        val stream = assets.open(path) ?: return notFound()
        val mime = mimeFor(path)
        val cacheable = req.param("v") != null
        val r: Response
        if (req.isHead) {
            stream.close()
            r = NanoHTTPD.newFixedLengthResponse(HttpStatus.OK, mime, ByteArrayInputStream(ByteArray(0)), 0)
            val len = assets.length(path)
            if (len >= 0) r.addHeader("Content-Length", len.toString())
        } else {
            val len = assets.length(path)
            r = if (len >= 0) NanoHTTPD.newFixedLengthResponse(HttpStatus.OK, mime, stream, len)
            else NanoHTTPD.newChunkedResponse(HttpStatus.OK, mime, stream)
            if (isCompressible(mime)) HttpResponses.allowGzip(r)
        }
        r.addHeader(
            "Cache-Control",
            if (cacheable) "public, max-age=31536000, immutable" else "no-cache",
        )
        return r
    }

    private fun notFound(): Response =
        HttpResponses.error(HttpStatus.NOT_FOUND, "NOT_FOUND", "not found")

    /** Maps a request URI to an asset path, or null if outside the allow-list. */
    internal fun resolve(uri: String): String? {
        val raw = uri.removePrefix("/")
        val path = if (raw.isEmpty()) "index.html" else raw
        if (path.contains('\u0000') || path.contains('\\') || path.endsWith("/")) return null
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (path in topLevel) return path
        return if (dirs.any { path.startsWith(it) } && path.contains('.')) path else null
    }

    private fun isCompressible(mime: String): Boolean {
        val m = mime.substringBefore(';')
        return m.startsWith("text/") || m == "application/json" || m == "application/javascript" ||
            m == "image/svg+xml"
    }

    companion object {
        fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "txt", "md" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
