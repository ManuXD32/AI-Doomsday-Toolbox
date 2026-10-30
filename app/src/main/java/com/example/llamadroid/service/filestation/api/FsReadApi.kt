package com.example.llamadroid.service.filestation.api

import com.example.llamadroid.service.filestation.http.ApiRequest
import com.example.llamadroid.service.filestation.http.FileStationDeps
import com.example.llamadroid.service.filestation.http.HttpError
import com.example.llamadroid.service.filestation.http.HttpResponses
import com.example.llamadroid.service.filestation.http.HttpStatus
import com.example.llamadroid.service.filestation.http.RawResponder
import com.example.llamadroid.service.filestation.http.Route
import com.example.llamadroid.service.filestation.http.RouteModule
import com.example.llamadroid.service.filestation.http.ByteRange
import com.example.llamadroid.service.filestation.volume.FileStationVolume
import com.example.llamadroid.service.filestation.volume.FsEntry
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.FsPaths
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/** Shared volume/path resolution for the API modules. */
internal fun ApiRequest.volume(name: String = "vol"): FileStationVolume {
    val id = requireParam(name)
    return deps.volumes.get(id) ?: throw FsException(FsErrorCode.NOT_FOUND, "unknown volume: $id")
}

internal fun FileStationDeps.volumeOrThrow(id: String): FileStationVolume =
    volumes.get(id) ?: throw FsException(FsErrorCode.NOT_FOUND, "unknown volume: $id")

/** `/api/volumes`, `/api/list`, `/api/stat`, `/api/search`, `/api/usage` and `/raw/<vol>/<path>`. */
class FsReadApi(private val deps: FileStationDeps) : RouteModule {
    override fun routes() = listOf(
        Route(Method.GET, "/api/volumes", { volumes() }),
        Route(Method.GET, "/api/list", { list(it) }),
        Route(Method.GET, "/api/stat", { stat(it) }),
        Route(Method.GET, "/api/search", { search(it) }),
        Route(Method.GET, "/api/usage", { usage(it) }),
        Route(Method.GET, "/raw/", { raw(it) }, prefix = true),
    )

    private fun volumes(): Response = HttpResponses.json(buildJsonObject {
        put("volumes", buildJsonArray {
            deps.volumes.list().forEach { v ->
                add(buildJsonObject {
                    put("id", v.id)
                    put("label", v.label)
                    put("readOnly", v.readOnly)
                    put("freeBytes", runCatching { v.freeBytes() }.getOrDefault(-1L))
                })
            }
        })
    })

    private fun showHidden(req: ApiRequest): Boolean = req.boolParam("hidden") || deps.volumes.showHidden

    private fun list(req: ApiRequest): Response {
        val vol = req.volume()
        val path = req.pathParam("path")
        val hidden = showHidden(req)
        val sort = req.param("sort") ?: "name"
        val desc = req.param("order") == "desc"
        var entries = vol.list(path)
        if (!hidden) entries = entries.filter { !FsPaths.isHiddenName(it.name) }
        val nameCmp = compareBy<FsEntry> { it.name.lowercase() }.thenBy { it.name }
        val cmp: Comparator<FsEntry> = when (sort) {
            "size" -> compareBy<FsEntry> { it.size }.then(nameCmp)
            "mtime" -> compareBy<FsEntry> { it.mtime }.then(nameCmp)
            else -> nameCmp
        }
        val ordered = if (desc) cmp.reversed() else cmp
        val sorted = entries.sortedWith(compareByDescending<FsEntry> { it.isDir }.then(ordered))
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("$sort|$desc|$hidden|${vol.readOnly}".toByteArray())
        sorted.forEach { digest.update("${it.name}|${it.etag};".toByteArray()) }
        val etag = "\"" + digest.digest().joinToString("") { "%02x".format(it) }.take(24) + "\""
        if (ByteRange.noneMatchHit(req.header("if-none-match"), etag)) {
            return HttpResponses.empty(HttpStatus.NOT_MODIFIED).also { it.addHeader("ETag", etag) }
        }
        val r = HttpResponses.json(buildJsonObject {
            put("vol", vol.id)
            put("path", path)
            put("readOnly", vol.readOnly)
            put("entries", buildJsonArray { sorted.forEach { add(HttpResponses.entryJson(it)) } })
        })
        r.addHeader("ETag", etag)
        r.addHeader("Cache-Control", "no-cache")
        return r
    }

    private fun stat(req: ApiRequest): Response =
        HttpResponses.json(HttpResponses.entryJson(req.volume().stat(req.pathParam("path"))))

    private fun search(req: ApiRequest): Response {
        val q = req.requireParam("q").trim().lowercase()
        if (q.isEmpty()) throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "empty query")
        val limit = req.longParam("limit", 200).coerceIn(1, 1000).toInt()
        val hidden = showHidden(req)
        val start = req.pathParam("path")
        val vols = if (req.param("vol") == null) deps.volumes.list() else listOf(req.volume())
        val results = ArrayList<Pair<String, FsEntry>>()
        var truncated = false
        var visited = 0
        outer@ for (v in vols) {
            val root = if (req.param("vol") == null) "" else start
            val prefix = if (root.isEmpty()) "" else "$root/"
            for (e in v.walk(root)) {
                if (++visited > MAX_WALK) { truncated = true; break@outer }
                if (!hidden && e.path.removePrefix(prefix).split('/').any { FsPaths.isHiddenName(it) }) continue
                if (e.name.lowercase().contains(q)) {
                    if (results.size >= limit) { truncated = true; break@outer }
                    results += v.id to e
                }
            }
        }
        return HttpResponses.json(buildJsonObject {
            put("results", buildJsonArray { results.forEach { (id, e) -> add(HttpResponses.entryJson(e, id)) } })
            put("truncated", truncated)
        })
    }

    private fun usage(req: ApiRequest): Response {
        val vol = req.volume()
        val path = req.pathParam("path")
        val hidden = showHidden(req)
        var files = 0L
        var dirs = 0L
        var bytes = 0L
        var visited = 0
        var truncated = false
        val perChild = HashMap<String, Long>()
        val prefix = if (path.isEmpty()) "" else "$path/"
        for (e in vol.walk(path)) {
            if (++visited > MAX_WALK) { truncated = true; break }
            val rel = e.path.removePrefix(prefix)
            if (!hidden && rel.split('/').any { FsPaths.isHiddenName(it) }) continue
            if (e.isDir) { dirs++; continue }
            files++
            val size = maxOf(e.size, 0L)
            bytes += size
            val child = prefix + rel.substringBefore('/')
            perChild.merge(child, size, Long::plus)
        }
        val top = perChild.entries.sortedByDescending { it.value }.take(20)
        return HttpResponses.json(buildJsonObject {
            put("files", files)
            put("dirs", dirs)
            put("bytes", bytes)
            put("freeBytes", runCatching { vol.freeBytes() }.getOrDefault(-1L))
            put("truncated", truncated)
            put("top", buildJsonArray {
                top.forEach { add(buildJsonObject { put("path", it.key); put("bytes", it.value) }) }
            })
        })
    }

    private fun raw(req: ApiRequest): Response {
        val rest = req.uri.removePrefix("/raw/")
        val volId = rest.substringBefore('/')
        val vol = deps.volumeOrThrow(volId)
        val path = FsPaths.normalize(rest.substringAfter('/', ""))
        val entry = vol.stat(path)
        if (entry.isDir) throw FsException(FsErrorCode.INVALID_PATH, "is a directory")
        val mime = withCharset(entry.mime)
        val headers = LinkedHashMap<String, String>()
        headers["Content-Disposition"] = HttpResponses.contentDisposition(entry.name, req.boolParam("download"))
        if (HttpResponses.isActiveContent(entry.mime)) {
            headers["Content-Security-Policy"] = "sandbox"
        }
        return RawResponder.respond(req, maxOf(entry.size, 0L), entry.etag, mime, headers) { off ->
            vol.openRead(path, off)
        }
    }

    private fun withCharset(mime: String): String {
        val m = mime.lowercase()
        val textual = m.startsWith("text/") || m == "application/json" || m == "application/xml" ||
            m == "application/javascript"
        return if (textual && !m.contains("charset")) "$mime; charset=utf-8" else mime
    }

    companion object {
        private const val MAX_WALK = 200_000
    }
}
