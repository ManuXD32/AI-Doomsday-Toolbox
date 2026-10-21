package com.example.llamadroid.service.filestation.api

import com.example.llamadroid.service.filestation.http.ApiRequest
import com.example.llamadroid.service.filestation.http.FileStationDeps
import com.example.llamadroid.service.filestation.http.HttpError
import com.example.llamadroid.service.filestation.http.HttpResponses
import com.example.llamadroid.service.filestation.http.HttpStatus
import com.example.llamadroid.service.filestation.http.Route
import com.example.llamadroid.service.filestation.http.RouteModule
import com.example.llamadroid.service.filestation.http.array
import com.example.llamadroid.service.filestation.http.asObject
import com.example.llamadroid.service.filestation.http.bool
import com.example.llamadroid.service.filestation.http.requireString
import com.example.llamadroid.service.filestation.http.string
import com.example.llamadroid.service.filestation.volume.ConflictPolicy
import com.example.llamadroid.service.filestation.volume.FsEntry
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.FsPaths
import com.example.llamadroid.service.filestation.volume.TrashStore
import com.example.llamadroid.service.filestation.volume.VolumeRegistry
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/** mkdir, newfile, rename, move, copy, delete (to trash) and the trash endpoints. */
class FsWriteApi(
    private val deps: FileStationDeps,
    private val registry: VolumeRegistry,
    private val trash: TrashStore,
) : RouteModule {
    override fun routes() = listOf(
        Route(Method.POST, "/api/mkdir", { mkdir(it) }),
        Route(Method.POST, "/api/newfile", { newFile(it) }),
        Route(Method.POST, "/api/rename", { rename(it) }),
        Route(Method.POST, "/api/move", { transfer(it, move = true) }),
        Route(Method.POST, "/api/copy", { transfer(it, move = false) }),
        Route(Method.POST, "/api/delete", { delete(it) }),
        Route(Method.GET, "/api/trash", { trashList(it) }),
        Route(Method.POST, "/api/trash/restore", { trashRestore(it) }),
        Route(Method.POST, "/api/trash/empty", { trashEmpty(it) }),
    )

    private class Item(val vol: String, val path: String)

    private fun items(body: JsonObject): List<Item> {
        val arr = body.array("items")
        if (arr.isEmpty() || arr.size > MAX_ITEMS) {
            throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "items must hold 1..$MAX_ITEMS entries")
        }
        return arr.map {
            val o = it.asObject()
            Item(o.requireString("vol"), FsPaths.normalize(o.string("path").orEmpty()))
        }
    }

    private fun mkdir(req: ApiRequest): Response {
        val body = req.jsonBody()
        val volId = body.requireString("vol")
        val vol = registry.requireWritable(volId)
        val path = FsPaths.normalize(body.requireString("path"))
        if (path.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "empty path")
        vol.mkdirs(path)
        return HttpResponses.ok { put("entry", HttpResponses.entryJson(vol.stat(path))) }
    }

    private fun newFile(req: ApiRequest): Response {
        val body = req.jsonBody()
        val vol = registry.requireWritable(body.requireString("vol"))
        val dir = FsPaths.normalize(body.string("dir").orEmpty())
        val name = FsPaths.validateSegment(body.requireString("name"))
        val kind = body.string("kind") ?: "empty"
        if (kind != "md" && kind != "txt" && kind != "empty") {
            throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "bad kind")
        }
        val content = body.string("content").orEmpty().toByteArray(Charsets.UTF_8)
        if (!vol.stat(dir).isDir) throw FsException(FsErrorCode.NOT_FOUND, "not a folder: $dir")
        val path = FsPaths.join(dir, name)
        if (exists(vol, path)) throw FsException(FsErrorCode.EXISTS, "already exists: $name")
        vol.openWrite(path, append = false).use { it.write(content) }
        return HttpResponses.ok { put("entry", HttpResponses.entryJson(vol.stat(path))) }
    }

    private fun rename(req: ApiRequest): Response {
        val body = req.jsonBody()
        val name = FsPaths.validateSegment(body.requireString("name"))
        val entry = registry.rename(body.requireString("vol"), FsPaths.normalize(body.requireString("path")), name)
        return HttpResponses.ok { put("entry", HttpResponses.entryJson(entry)) }
    }

    private fun transfer(req: ApiRequest, move: Boolean): Response {
        val body = req.jsonBody()
        val list = items(body)
        val dstVol = body.requireString("dstVol")
        val dstDir = FsPaths.normalize(body.string("dstDir").orEmpty())
        val policy = ConflictPolicy.parse(body.string("conflict"))
        registry.requireWritable(dstVol)
        if (move) list.forEach { registry.requireWritable(it.vol) }
        list.forEach { registry.get(it.vol) ?: throw FsException(FsErrorCode.NOT_FOUND, "unknown volume: ${it.vol}") }
        // Reject folder-into-itself up front so it is a 400 even when the work would run as a job.
        list.forEach {
            if (it.vol == dstVol && it.path.isNotEmpty() && FsPaths.isSameOrInside(dstDir, it.path) &&
                registry.get(it.vol)?.stat(it.path)?.isDir == true
            ) throw FsException(FsErrorCode.INVALID_PATH, "cannot put a folder into itself")
        }
        if (estimateBytes(list) > INLINE_MAX_BYTES || (list.size > INLINE_MAX_ITEMS)) {
            val total = estimateBytes(list).coerceAtLeast(1)
            val id = deps.jobs.submit(
                if (move) "move" else "copy",
                (if (move) "Move " else "Copy ") + list.size + " item(s)",
            ) { ctx ->
                var base = 0L
                for (item in list) {
                    ctx.checkCancelled()
                    val baseNow = base
                    val e = registry.transfer(
                        item.vol, item.path, dstVol, dstDir, move, policy,
                        progress = { done -> ctx.progress(((baseNow + done).toFloat() / total).coerceIn(0f, 1f)) },
                        cancelled = { ctx.isCancelled },
                    )
                    if (e != null) ctx.addOutput(dstVol, e.path)
                    base += estimateBytes(listOf(item))
                }
            }
            return HttpResponses.json(buildJsonObject { put("jobId", id) }, HttpStatus.ACCEPTED)
        }
        val done = ArrayList<FsEntry>()
        for (item in list) {
            registry.transfer(item.vol, item.path, dstVol, dstDir, move, policy)?.let { e -> done += e }
        }
        return HttpResponses.ok { put(if (move) "moved" else "copied", entries(done)) }
    }

    private fun estimateBytes(list: List<Item>): Long {
        var total = 0L
        for (item in list) {
            val vol = registry.get(item.vol) ?: continue
            val e = try { vol.stat(item.path) } catch (_: FsException) { continue }
            if (!e.isDir) { total += maxOf(e.size, 0L); continue }
            var seen = 0
            for (c in vol.walk(item.path)) {
                if (++seen > ESTIMATE_WALK_CAP) return Long.MAX_VALUE / 4
                if (!c.isDir) total += maxOf(c.size, 0L)
            }
        }
        return total
    }

    private fun delete(req: ApiRequest): Response {
        val body = req.jsonBody()
        val list = items(body)
        val permanent = body.bool("permanent")
        var deleted = 0
        for (item in list) {
            if (item.path.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "cannot delete a volume root")
            if (permanent) {
                val vol = registry.requireWritable(item.vol)
                val entry = vol.stat(item.path)
                vol.delete(item.path)
                if (!entry.isDir && !FsPaths.isHiddenName(entry.name)) {
                    val att = FsPaths.attachmentsDirFor(item.path)
                    if (exists(vol, att)) vol.delete(att)
                }
            } else {
                trash.trash(item.vol, item.path)
            }
            deleted++
        }
        return HttpResponses.ok { put("deleted", deleted) }
    }

    private fun trashList(req: ApiRequest): Response {
        val volId = req.requireParam("vol")
        val items = trash.list(volId)
        return HttpResponses.json(buildJsonObject {
            put("items", buildJsonArray {
                items.forEach {
                    add(buildJsonObject {
                        put("id", it.id)
                        put("vol", it.vol)
                        put("name", it.name)
                        put("originalPath", it.originalPath)
                        put("deletedAt", it.deletedAt)
                        put("size", it.size)
                        put("isDir", it.isDir)
                    })
                }
            })
        })
    }

    private fun trashRestore(req: ApiRequest): Response {
        val body = req.jsonBody()
        val ids = body.array("ids").map { HttpResponses.str(it) ?: throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "bad id") }
        val restored = trash.restore(body.requireString("vol"), ids, ConflictPolicy.parse(body.string("conflict")))
        return HttpResponses.ok { put("restored", entries(restored)) }
    }

    private fun trashEmpty(req: ApiRequest): Response {
        val body = req.jsonBody()
        val ids = (body["ids"] as? kotlinx.serialization.json.JsonArray)?.map {
            HttpResponses.str(it) ?: throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "bad id")
        }
        val removed = trash.empty(body.requireString("vol"), ids)
        return HttpResponses.ok { put("removed", removed) }
    }

    private fun entries(list: List<FsEntry>) = buildJsonArray { list.forEach { add(HttpResponses.entryJson(it)) } }

    private fun exists(vol: com.example.llamadroid.service.filestation.volume.FileStationVolume, path: String): Boolean =
        try { vol.stat(path); true } catch (e: FsException) {
            if (e.code == FsErrorCode.NOT_FOUND) false else throw e
        }

    companion object {
        private const val MAX_ITEMS = 1000
        private const val INLINE_MAX_BYTES = 32L shl 20
        private const val INLINE_MAX_ITEMS = 50
        private const val ESTIMATE_WALK_CAP = 5000
    }
}
