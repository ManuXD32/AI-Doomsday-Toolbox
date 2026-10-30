package com.example.llamadroid.service.filestation.api

import com.example.llamadroid.service.filestation.http.ApiRequest
import com.example.llamadroid.service.filestation.http.ByteRange
import com.example.llamadroid.service.filestation.http.FileStationDeps
import com.example.llamadroid.service.filestation.http.HttpError
import com.example.llamadroid.service.filestation.http.HttpResponses
import com.example.llamadroid.service.filestation.http.HttpStatus
import com.example.llamadroid.service.filestation.http.Route
import com.example.llamadroid.service.filestation.http.RouteModule
import com.example.llamadroid.service.filestation.volume.FileStationVolume
import com.example.llamadroid.service.filestation.volume.FsEntry
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.FsPaths
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Text load/save with SHA-256 ETags and pasted-image attachments for the Markdown editor. */
class EditorApi(private val deps: FileStationDeps) : RouteModule {
    override fun routes() = listOf(
        Route(Method.GET, "/api/text", { load(it) }),
        Route(Method.PUT, "/api/text", { save(it) }),
        Route(Method.PUT, "/api/attachment", { attachment(it) }),
    )

    private val locks = Array(32) { Any() }
    private fun lockFor(key: String): Any = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]

    private class Digest(val etag: String, val size: Long, val binary: Boolean)

    private fun digest(vol: FileStationVolume, path: String): Digest {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var binary = false
        vol.openRead(path).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (total < SNIFF_BYTES) {
                    val upto = minOf(n.toLong(), SNIFF_BYTES - total).toInt()
                    for (i in 0 until upto) if (buf[i].toInt() == 0) binary = true
                }
                total += n
                md.update(buf, 0, n)
            }
        }
        return Digest("\"" + hex(md.digest()) + "\"", total, binary)
    }

    private fun load(req: ApiRequest): Response {
        val vol = req.volume()
        val path = req.pathParam("path")
        val entry = vol.stat(path)
        if (entry.isDir) throw FsException(FsErrorCode.INVALID_PATH, "is a directory")
        if (entry.size > MAX_TEXT) throw HttpError(HttpStatus.TOO_LARGE, "TOO_LARGE", "file exceeds 32 MiB")
        val d = digest(vol, path)
        if (d.binary) throw FsException(FsErrorCode.UNSUPPORTED, "binary file")
        if (ByteRange.noneMatchHit(req.header("if-none-match"), d.etag)) {
            return HttpResponses.empty(HttpStatus.NOT_MODIFIED).also { it.addHeader("ETag", d.etag) }
        }
        val r = NanoHTTPD.newFixedLengthResponse(
            HttpStatus.OK, "text/plain; charset=utf-8", vol.openRead(path), d.size,
        )
        r.addHeader("ETag", d.etag)
        r.addHeader("Cache-Control", "no-cache")
        return r
    }

    private fun save(req: ApiRequest): Response {
        val volId = req.requireParam("vol")
        val vol = deps.volumes.requireWritable(volId)
        val path = req.pathParam("path")
        if (path.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "empty path")
        val create = req.boolParam("create")
        val ifMatch = req.header("if-match")
        // Reject before reading the (up to 32 MiB) body whenever the precondition already fails.
        synchronized(lockFor("$volId/$path")) {
            val existing = statOrNull(vol, path)
            if (existing != null) {
                if (existing.isDir) throw FsException(FsErrorCode.INVALID_PATH, "is a directory")
                val current = digest(vol, path).etag
                if (ifMatch == null || !ByteRange.matchHit(ifMatch, current)) throw stale(current)
            } else {
                if (!create) throw FsException(FsErrorCode.NOT_FOUND, "not found: $path")
                if (ifMatch != null && ifMatch.trim() != "*") throw stale(null)
                if (!vol.stat(FsPaths.parent(path)).isDir) throw FsException(FsErrorCode.NOT_FOUND, "parent is not a folder")
            }
            val tmpDir = File(deps.cacheDir, "file_station_tmp").also { it.mkdirs() }
            val tmp = File(tmpDir, "text-" + UUID.randomUUID())
            try {
                val md = MessageDigest.getInstance("SHA-256")
                FileOutputStream(tmp).use { out ->
                    val input = req.bodyStream(MAX_TEXT)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                    }
                }
                if (tmp.length() != req.contentLength) throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "body truncated")
                // Copy into the original with truncate so the document id and attachment links stay valid.
                FileInputStream(tmp).use { input ->
                    vol.openWrite(path, append = false).use { out -> input.copyTo(out, 64 * 1024) }
                }
                val etag = "\"" + hex(md.digest()) + "\""
                val entry = vol.stat(path)
                val r = HttpResponses.json(buildJsonObject {
                    put("etag", etag)
                    put("entry", HttpResponses.entryJson(entry))
                })
                r.addHeader("ETag", etag)
                return r
            } finally {
                tmp.delete()
            }
        }
    }

    private fun stale(current: String?): HttpError = HttpError(
        HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED", "file changed",
        extras = JsonObject(mapOf("currentEtag" to (current?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull))),
    )

    private fun attachment(req: ApiRequest): Response {
        val volId = req.requireParam("vol")
        val vol = deps.volumes.requireWritable(volId)
        val doc = req.pathParam("doc")
        val name = FsPaths.validateSegment(req.requireParam("name"))
        val mime = FsPaths.guessMime(name)
        if (!mime.startsWith("image/") || mime == "image/svg+xml") {
            throw FsException(FsErrorCode.UNSUPPORTED, "only raster images may be attached")
        }
        val docEntry = vol.stat(doc)
        if (docEntry.isDir) throw FsException(FsErrorCode.INVALID_PATH, "document is a folder")
        val dir = FsPaths.attachmentsDirFor(doc)
        vol.mkdirs(dir)
        val finalName = FsPaths.conflictName(name) { statOrNull(vol, FsPaths.join(dir, it)) != null }
        val path = FsPaths.join(dir, finalName)
        try {
            vol.openWrite(path, append = false).use { out -> req.copyBodyTo(out, MAX_ATTACHMENT) }
        } catch (t: Throwable) {
            runCatching { vol.delete(path) }
            throw t
        }
        val rel = FsPaths.ATTACHMENTS_PREFIX + FsPaths.name(doc) + "/" + finalName
        return HttpResponses.json(buildJsonObject {
            put("path", rel)
            put("url", rel.split('/').joinToString("/") {
                java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            })
        })
    }

    private fun statOrNull(vol: FileStationVolume, path: String): FsEntry? =
        try { vol.stat(path) } catch (e: FsException) {
            if (e.code == FsErrorCode.NOT_FOUND) null else throw e
        }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_TEXT = 32L shl 20
        private const val MAX_ATTACHMENT = 25L shl 20
        private const val SNIFF_BYTES = 8192L
    }
}
