package com.example.llamadroid.service.filestation.api

import com.example.llamadroid.service.filestation.http.ApiRequest
import com.example.llamadroid.service.filestation.http.FileStationDeps
import com.example.llamadroid.service.filestation.http.HttpError
import com.example.llamadroid.service.filestation.http.HttpResponses
import com.example.llamadroid.service.filestation.http.HttpStatus
import com.example.llamadroid.service.filestation.http.JobContext
import com.example.llamadroid.service.filestation.http.Route
import com.example.llamadroid.service.filestation.http.RouteModule
import com.example.llamadroid.service.filestation.http.VolumeRegistryApi
import com.example.llamadroid.service.filestation.http.array
import com.example.llamadroid.service.filestation.http.asObject
import com.example.llamadroid.service.filestation.http.long
import com.example.llamadroid.service.filestation.http.requireString
import com.example.llamadroid.service.filestation.http.string
import com.example.llamadroid.service.filestation.jobs.StagingJobContext
import com.example.llamadroid.service.filestation.tools.ArchiveLimits
import com.example.llamadroid.service.filestation.tools.ArchiveTools
import com.example.llamadroid.service.filestation.tools.DataTools
import com.example.llamadroid.service.filestation.tools.TaskControl
import com.example.llamadroid.service.filestation.tools.VolumeItem
import com.example.llamadroid.service.filestation.volume.ConflictPolicy
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.FsPaths
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.io.FilterInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Zip download (streamed through a pipe fed from the job pool), zip creation, extraction and the
 * data tools (sha256, duplicates). Mount through `extraRoutes` of the server.
 */
class ArchiveApi(
    private val deps: FileStationDeps,
    private val registry: VolumeRegistryApi = deps.volumes,
    limits: ArchiveLimits = ArchiveLimits(),
) : RouteModule {
    private val archives = ArchiveTools(limits)

    override fun routes() = listOf(
        Route(Method.GET, "/api/zip", { download(it) }),
        Route(Method.POST, "/api/archive/zip", { zip(it) }),
        Route(Method.POST, "/api/archive/extract", { extract(it) }),
        Route(Method.POST, "/api/tools/sha256", { sha256(it) }),
        Route(Method.POST, "/api/tools/duplicates", { duplicates(it) }),
    )

    private fun volume(id: String) =
        registry.get(id) ?: throw FsException(FsErrorCode.NOT_FOUND, "unknown volume: $id")

    private fun accepted(jobId: String): Response =
        HttpResponses.json(buildJsonObject { put("jobId", jobId) }, HttpStatus.ACCEPTED)

    private fun items(body: JsonObject, max: Int = MAX_ITEMS): List<VolumeItem> {
        val arr = body.array("items")
        if (arr.isEmpty() || arr.size > max) {
            throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "items must hold 1..$max entries")
        }
        return arr.map {
            val o = it.asObject()
            VolumeItem(volume(o.requireString("vol")), FsPaths.normalize(o.string("path").orEmpty()))
        }
    }

    // ---- GET /api/zip

    private fun download(req: ApiRequest): Response {
        val vol = volume(req.requireParam("vol"))
        val paths = req.params("path").map { FsPaths.normalize(it) }.ifEmpty { listOf("") }.distinct()
        if (paths.size > MAX_ITEMS) throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "too many paths")
        val sources = paths.map { VolumeItem(vol, it) }
        sources.forEach { vol.stat(it.path) } // NOT_FOUND before any byte is streamed
        val base = when {
            sources.size > 1 -> "selection"
            sources[0].path.isEmpty() -> vol.id
            else -> FsPaths.name(sources[0].path)
        }
        val fileName = "$base.zip"
        val response: Response
        if (req.isHead) {
            response = HttpResponses.empty(HttpStatus.OK)
        } else {
            val pipeIn = PipedInputStream(PIPE_BUFFER)
            val pipeOut = PipedOutputStream(pipeIn)
            val jobId = AtomicReference<String?>()
            jobId.set(
                deps.jobs.submit("zip-download", "Zip $fileName") { ctx ->
                    try {
                        archives.writeZip(sources, pipeOut, ZIP_DOWNLOAD_LEVEL, TaskControl.of(ctx))
                    } finally {
                        runCatching { pipeOut.close() }
                    }
                },
            )
            // Closing the response body (client gone) cancels the job; the writer then fails on the closed pipe.
            val body = object : FilterInputStream(pipeIn) {
                override fun close() {
                    super.close()
                    jobId.get()?.let { deps.jobs.cancel(it) }
                }
            }
            response = NanoHTTPD.newChunkedResponse(HttpStatus.OK, "application/zip", body)
        }
        response.addHeader("Content-Type", "application/zip")
        response.addHeader("Content-Disposition", HttpResponses.contentDisposition(fileName, attachment = true))
        return response
    }

    // ---- POST /api/archive/zip and /extract

    private fun zip(req: ApiRequest): Response {
        val body = req.jsonBody()
        val sources = items(body)
        val dstVol = registry.requireWritable(body.requireString("dstVol"))
        val dstDir = FsPaths.normalize(body.string("dstDir").orEmpty())
        var name = FsPaths.validateSegment(body.requireString("name"))
        if (!name.endsWith(".zip", ignoreCase = true)) name += ".zip"
        val level = (body.long("level") ?: ZIP_DOWNLOAD_LEVEL.toLong()).toInt().coerceIn(0, 9)
        val policy = ConflictPolicy.parse(body.string("conflict"))
        val jobId = deps.jobs.submit("archive-zip", "Zip $name") { ctx ->
            val created = archives.createZipToVolume(sources, dstVol, dstDir, name, level, policy, TaskControl.of(ctx))
            if (created != null) ctx.addOutput(dstVol.id, created) else ctx.progress(1f, "skipped")
        }
        return accepted(jobId)
    }

    private fun extract(req: ApiRequest): Response {
        val body = req.jsonBody()
        val src = VolumeItem(volume(body.requireString("vol")), FsPaths.normalize(body.requireString("path")))
        val dstVol = registry.requireWritable(body.requireString("dstVol"))
        val dstDir = FsPaths.normalize(body.string("dstDir").orEmpty())
        val policy = ConflictPolicy.parse(body.string("conflict"))
        val password = body.string("password")?.takeIf { it.isNotEmpty() }?.toCharArray()
        if (!archives.isSupportedArchive(FsPaths.name(src.path))) {
            throw FsException(FsErrorCode.UNSUPPORTED, "unsupported archive type")
        }
        val jobId = deps.jobs.submit("archive-extract", "Extract ${FsPaths.name(src.path)}") { ctx ->
            withStaging(ctx) { staging ->
                val r = archives.extract(src, dstVol, dstDir, policy, password, staging, TaskControl.of(ctx))
                if (r != null) {
                    ctx.addOutput(dstVol.id, r.path)
                    ctx.progress(1f, "${r.files} files, ${r.skipped} skipped")
                } else {
                    ctx.progress(1f, "skipped")
                }
            }
        }
        return accepted(jobId)
    }

    private fun <T> withStaging(ctx: JobContext, block: (File) -> T): T {
        (ctx as? StagingJobContext)?.let { return block(it.stagingDir) }
        val dir = File(deps.cacheDir, "file_station_jobs/adhoc-${System.nanoTime()}").also { it.mkdirs() }
        try {
            return block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- data tools

    private fun sha256(req: ApiRequest): Response {
        val sources = items(req.jsonBody())
        val jobId = deps.jobs.submit("sha256", "SHA-256") { ctx ->
            val hashes = DataTools.sha256Items(sources.map { it.vol to it.path }, TaskControl.of(ctx))
            ctx.setResult(DataTools.hashesJson(hashes).toString())
        }
        return accepted(jobId)
    }

    private fun duplicates(req: ApiRequest): Response {
        val body = req.jsonBody()
        val vol = volume(body.requireString("vol"))
        val path = FsPaths.normalize(body.string("path").orEmpty())
        val minSize = body.long("minSize") ?: 1L
        val jobId = deps.jobs.submit("duplicates", "Find duplicates") { ctx ->
            val r = DataTools.findDuplicates(vol, path, minSize, TaskControl.of(ctx))
            ctx.setResult(DataTools.duplicatesJson(r).toString())
        }
        return accepted(jobId)
    }

    private companion object {
        const val MAX_ITEMS = 1000
        const val PIPE_BUFFER = 256 * 1024
        const val ZIP_DOWNLOAD_LEVEL = 6
    }
}
