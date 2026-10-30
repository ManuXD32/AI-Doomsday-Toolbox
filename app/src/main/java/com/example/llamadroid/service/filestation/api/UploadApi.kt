package com.example.llamadroid.service.filestation.api

import com.example.llamadroid.service.filestation.http.ApiRequest
import com.example.llamadroid.service.filestation.http.FileStationDeps
import com.example.llamadroid.service.filestation.http.HttpError
import com.example.llamadroid.service.filestation.http.HttpResponses
import com.example.llamadroid.service.filestation.http.HttpStatus
import com.example.llamadroid.service.filestation.http.Route
import com.example.llamadroid.service.filestation.http.RouteModule
import com.example.llamadroid.service.filestation.volume.ConflictPolicy
import com.example.llamadroid.service.filestation.volume.FileStationVolume
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.FsPaths
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Chunked, resumable upload (design section 3). Never uses NanoHTTPD parseBody. */
class UploadApi(private val deps: FileStationDeps) : RouteModule {
    override fun routes() = listOf(
        Route(Method.PUT, "/api/upload", { chunk(it) }),
        Route(Method.GET, "/api/upload/status", { status(it) }),
        Route(Method.DELETE, "/api/upload", { abort(it) }),
    )

    private class Target(val vol: FileStationVolume, val dir: String, val name: String, val partial: String)

    private val locks = Array(64) { Any() }

    private fun resolve(req: ApiRequest, writable: Boolean): Target {
        val volId = req.requireParam("vol")
        val vol = if (writable) deps.volumes.requireWritable(volId) else deps.volumeOrThrow(volId)
        val id = req.requireParam("uploadId")
        if (!ID_REGEX.matches(id)) throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "bad uploadId")
        val dir = req.pathParam("dir")
        val rel = FsPaths.normalize(req.requireParam("rel"))
        if (rel.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "empty rel")
        if ((dir + "/" + rel).split('/').any { FsPaths.isHiddenName(it) }) {
            throw FsException(FsErrorCode.INVALID_PATH, "hidden names are not allowed")
        }
        val relParent = FsPaths.parent(rel)
        val targetDir = if (relParent.isEmpty()) dir else FsPaths.join(dir, relParent)
        val name = FsPaths.name(rel)
        val partial = FsPaths.join(targetDir, FsPaths.validateSegment(".$name.fsupload-$id"))
        return Target(vol, targetDir, name, partial)
    }

    private fun size(vol: FileStationVolume, path: String): Long =
        try { vol.stat(path).takeIf { !it.isDir }?.size?.coerceAtLeast(0) ?: 0L } catch (e: FsException) {
            if (e.code == FsErrorCode.NOT_FOUND) 0L else throw e
        }

    private fun exists(vol: FileStationVolume, path: String): Boolean =
        try { vol.stat(path); true } catch (e: FsException) {
            if (e.code == FsErrorCode.NOT_FOUND) false else throw e
        }

    private fun chunk(req: ApiRequest): Response {
        val t = resolve(req, writable = true)
        val offset = req.longParam("offset", 0)
        val total = req.longParam("total", -1)
        if (offset < 0 || total < 0) throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "offset and total required")
        val policy = ConflictPolicy.parse(req.param("conflict"))
        val len = req.contentLength
            ?: throw HttpError(HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED", "Content-Length required")
        if (len > MAX_CHUNK) throw HttpError(HttpStatus.TOO_LARGE, "TOO_LARGE", "chunk exceeds 16 MiB")
        if (offset + len > total) throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "chunk exceeds total")
        synchronized(locks[(t.partial.hashCode() and Int.MAX_VALUE) % locks.size]) {
            if (offset == 0L) {
                val free = t.vol.freeBytes()
                if (free in 0 until total) throw FsException(FsErrorCode.NO_SPACE, "not enough free space")
                t.vol.mkdirs(t.dir)
            } else {
                val received = size(t.vol, t.partial)
                if (received != offset) throw conflict(received)
            }
            // Body is copied exactly Content-Length bytes straight into the partial file.
            t.vol.openWrite(t.partial, append = offset > 0).use { out -> req.copyBodyTo(out, MAX_CHUNK) }
            val received = offset + len
            if (received < total) {
                return HttpResponses.json(buildJsonObject { put("received", received); put("done", false) })
            }
            return finish(t, policy, received)
        }
    }

    private fun finish(t: Target, policy: ConflictPolicy, received: Long): Response {
        var name = t.name
        val existing = FsPaths.join(t.dir, name)
        if (exists(t.vol, existing)) {
            when (policy) {
                ConflictPolicy.SKIP -> {
                    t.vol.delete(t.partial)
                    return HttpResponses.json(buildJsonObject {
                        put("received", received); put("done", true); put("skipped", true)
                    })
                }
                ConflictPolicy.OVERWRITE -> t.vol.delete(existing)
                ConflictPolicy.RENAME ->
                    name = FsPaths.conflictName(name) { exists(t.vol, FsPaths.join(t.dir, it)) }
            }
        }
        val finalPath = try {
            t.vol.rename(t.partial, name)
        } catch (e: FsException) {
            if (e.code != FsErrorCode.EXISTS) throw e
            val alt = FsPaths.conflictName(name) { exists(t.vol, FsPaths.join(t.dir, it)) }
            t.vol.rename(t.partial, alt)
        }
        val entry = t.vol.stat(finalPath)
        return HttpResponses.json(buildJsonObject {
            put("received", received)
            put("done", true)
            put("entry", HttpResponses.entryJson(entry))
        })
    }

    private fun conflict(received: Long): HttpError = HttpError(
        HttpStatus.CONFLICT, "CONFLICT", "offset does not match received bytes",
        extras = JsonObject(mapOf("received" to kotlinx.serialization.json.JsonPrimitive(received))),
    )

    private fun status(req: ApiRequest): Response {
        val t = resolve(req, writable = false)
        return HttpResponses.json(buildJsonObject { put("received", size(t.vol, t.partial)) })
    }

    private fun abort(req: ApiRequest): Response {
        val t = resolve(req, writable = true)
        if (exists(t.vol, t.partial)) t.vol.delete(t.partial)
        return HttpResponses.ok()
    }

    companion object {
        const val MAX_CHUNK = 16L shl 20
        private val ID_REGEX = Regex("[A-Za-z0-9_-]{8,64}")
    }
}
