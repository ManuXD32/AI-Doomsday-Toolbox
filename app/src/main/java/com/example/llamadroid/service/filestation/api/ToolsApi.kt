package com.example.llamadroid.service.filestation.api

import com.example.llamadroid.service.filestation.http.ApiRequest
import com.example.llamadroid.service.filestation.http.ByteRange
import com.example.llamadroid.service.filestation.http.FileStationDeps
import com.example.llamadroid.service.filestation.http.HttpError
import com.example.llamadroid.service.filestation.http.HttpResponses
import com.example.llamadroid.service.filestation.http.HttpStatus
import com.example.llamadroid.service.filestation.http.JobContext
import com.example.llamadroid.service.filestation.http.Route
import com.example.llamadroid.service.filestation.http.RouteModule
import com.example.llamadroid.service.filestation.http.array
import com.example.llamadroid.service.filestation.http.asObject
import com.example.llamadroid.service.filestation.http.requireString
import com.example.llamadroid.service.filestation.http.string
import com.example.llamadroid.service.filestation.tools.MediaTools
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import com.example.llamadroid.service.filestation.volume.FsPaths
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Conversion tools (docs/file-station.md section 10): capabilities, thumbnails, QR and the
 * image/pdf/doc/media jobs. A null backend answers 501. Inputs are staged into
 * `cacheDir/file_station_jobs/tools-<uuid>` when a backend needs a File; outputs are copied back to
 * a writable volume with the requested conflict policy.
 */
class ToolsApi(private val deps: FileStationDeps) : RouteModule {
    private val thumbDir = File(deps.cacheDir, "file_station_thumbs")
    private val thumbGate = Semaphore(2)

    override fun routes(): List<Route> = listOf(
        Route(Method.GET, "/api/tools/capabilities", { capabilities() }),
        Route(Method.GET, "/api/tools/qr", { qr(it) }),
        Route(Method.GET, "/thumb/", { thumb(it) }, prefix = true),
        post("/api/tools/image/convert") { imageJob(it, "convert") },
        post("/api/tools/image/resize") { imageJob(it, "resize") },
        post("/api/tools/image/compress") { imageJob(it, "compress") },
        post("/api/tools/pdf/merge") { pdfMerge(it) },
        post("/api/tools/pdf/split") { pdfSplit(it) },
        post("/api/tools/pdf/images2pdf") { imagesToPdf(it) },
        post("/api/tools/pdf/pdf2images") { pdfToImages(it) },
        post("/api/tools/pdf/pdf2text") { pdfToText(it) },
        post("/api/tools/doc/topdf") { docToPdf(it) },
        post("/api/tools/doc/totext") { officeToText(it) },
        post("/api/tools/doc/csv2json") { csvJson(it, toJson = true) },
        post("/api/tools/doc/json2csv") { csvJson(it, toJson = false) },
        post("/api/tools/media/convert") { mediaConvert(it) },
        post("/api/tools/media/trim") { mediaTrim(it) },
    )

    private fun post(path: String, h: (ApiRequest) -> Response) = Route(Method.POST, path, { h(it) })

    // ---- capabilities / qr / thumbnails ----

    private fun capabilities(): Response {
        val t = deps.tools
        val media = t.media?.available == true
        return HttpResponses.json(buildJsonObject {
            put("image", t.image != null)
            put("pdf", t.pdf != null)
            put("doc", t.doc != null)
            put("media", media)
            put("qr", t.qr != null)
            put("mediaPresets", buildJsonArray { if (media) MediaTools.PRESET_IDS.forEach { add(it) } })
        })
    }

    private fun qr(req: ApiRequest): Response {
        val backend = deps.tools.qr ?: throw notImplemented("qr")
        val text = req.requireParam("text")
        if (text.isEmpty() || text.length > MAX_QR_CHARS) {
            throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "text must be 1..$MAX_QR_CHARS characters")
        }
        val size = req.longParam("size", 256).coerceIn(128, 1024).toInt()
        val bytes = backend.png(text, size)
        return bytesResponse("image/png", bytes, "private, max-age=3600")
    }

    private fun thumb(req: ApiRequest): Response {
        val image = deps.tools.image ?: throw notImplemented("image")
        val rest = req.uri.removePrefix("/thumb/")
        val vol = deps.volumeOrThrow(rest.substringBefore('/'))
        val path = FsPaths.normalize(rest.substringAfter('/', ""))
        val entry = vol.stat(path)
        if (entry.isDir) throw FsException(FsErrorCode.INVALID_PATH, "is a directory")
        if (!entry.mime.startsWith("image/") || entry.mime.contains("svg") || entry.size > MAX_THUMB_SOURCE) {
            throw FsException(FsErrorCode.UNSUPPORTED, "not a thumbnail-capable image")
        }
        val size = req.longParam("size", 256).coerceIn(64, 512).toInt()
        val key = sha1("${vol.id}|$path|$size|${entry.etag}")
        val etag = "\"$key\""
        if (ByteRange.noneMatchHit(req.header("if-none-match"), etag)) {
            return HttpResponses.empty(HttpStatus.NOT_MODIFIED).also { it.addHeader("ETag", etag) }
        }
        val cached = File(thumbDir, "$key.jpg")
        val bytes = if (cached.isFile && cached.length() > 0) {
            cached.setLastModified(System.currentTimeMillis())
            cached.readBytes()
        } else {
            if (!thumbGate.tryAcquire(5, TimeUnit.SECONDS)) {
                throw HttpError(HttpStatus.BUSY, "BUSY", "thumbnail workers busy", headers = mapOf("Retry-After" to "2"))
            }
            try {
                val staging = File(deps.cacheDir, "file_station_jobs/thumb-${UUID.randomUUID()}")
                try {
                    val input = stage(vol.id, path, File(staging, "0"))
                    val data = image.thumbnail(input, size)
                        ?: throw FsException(FsErrorCode.UNSUPPORTED, "not a decodable image")
                    thumbDir.mkdirs()
                    runCatching { File(thumbDir, "$key.jpg").writeBytes(data) }
                    pruneThumbs()
                    data
                } finally {
                    staging.deleteRecursively()
                }
            } finally {
                thumbGate.release()
            }
        }
        return bytesResponse("image/jpeg", bytes, "private, max-age=86400").also { it.addHeader("ETag", etag) }
    }

    private fun pruneThumbs() {
        val files = thumbDir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_THUMB_CACHE) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= MAX_THUMB_CACHE * 3 / 4) break
        }
    }

    private fun bytesResponse(mime: String, bytes: ByteArray, cache: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(HttpStatus.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
        r.addHeader("Cache-Control", cache)
        return r
    }

    // ---- image ----

    private fun imageJob(req: ApiRequest, op: String): Response {
        val backend = deps.tools.image ?: throw notImplemented("image")
        val body = req.jsonBody()
        val spec = ToolSpec.parse(deps, body, multi = true)
        val quality = intField(body, "quality", 1, 100) ?: if (op == "compress") 70 else 85
        val maxW = intField(body, "maxWidth", 1, MAX_DIM)
        val maxH = intField(body, "maxHeight", 1, MAX_DIM)
        val fmtRaw = body.string("format")?.lowercase()?.let { if (it == "jpg") "jpeg" else it }
        if (fmtRaw != null && fmtRaw !in IMAGE_FORMATS) badRequest("format must be jpeg, png or webp")
        if (op == "convert" && fmtRaw == null) badRequest("missing field: format")
        if (op == "resize" && maxW == null && maxH == null) badRequest("maxWidth or maxHeight required")
        val suffix = when (op) { "resize" -> "-resized"; "compress" -> "-compressed"; else -> "" }
        return runJob("image.$op", "Image $op", spec) { ctx, io ->
            spec.items.forEachIndexed { i, item ->
                ctx.checkCancelled()
                val input = io.stage(item, i)
                val fmt = fmtRaw ?: formatForName(item.name)
                val out = File(io.outDir, "$i.${extFor(fmt)}")
                backend.convert(input, out, fmt, quality, maxW, maxH)
                io.emit(ctx, FsPaths.splitExtension(item.name).first + suffix + "." + extFor(fmt), out)
                ctx.progress((i + 1f) / spec.items.size)
            }
        }
    }

    // ---- pdf ----

    private fun pdfMerge(req: ApiRequest): Response {
        val backend = deps.tools.pdf ?: throw notImplemented("pdf")
        val body = req.jsonBody()
        val spec = ToolSpec.parse(deps, body, multi = true)
        if (spec.items.size < 2) badRequest("at least two PDFs are required")
        val name = outName(body.string("name"), "merged", "pdf")
        return runJob("pdf.merge", "Merge PDFs", spec) { ctx, io ->
            val inputs = spec.items.mapIndexed { i, it -> io.stage(it, i) }
            val out = File(io.outDir, "merged.pdf")
            backend.merge(inputs, out, ctx)
            io.emit(ctx, name, out)
        }
    }

    private fun pdfSplit(req: ApiRequest): Response {
        val backend = deps.tools.pdf ?: throw notImplemented("pdf")
        val body = req.jsonBody()
        val spec = ToolSpec.parse(deps, body, multi = false)
        val ranges = body.requireString("ranges").replace(" ", "")
        if (!RANGES.matches(ranges)) badRequest("ranges must look like 1-3,5")
        return runJob("pdf.split", "Split PDF", spec) { ctx, io ->
            val input = io.stage(spec.items[0], 0)
            val files = backend.split(input, ranges, io.outDir, ctx)
            files.forEach { io.emit(ctx, it.name, it) }
        }
    }

    private fun imagesToPdf(req: ApiRequest): Response {
        val backend = deps.tools.pdf ?: throw notImplemented("pdf")
        val body = req.jsonBody()
        val spec = ToolSpec.parse(deps, body, multi = true)
        val name = outName(body.string("name"), "images", "pdf")
        return runJob("pdf.images2pdf", "Images to PDF", spec) { ctx, io ->
            val images = spec.items.mapIndexed { i, it -> io.stage(it, i) }
            val out = File(io.outDir, "images.pdf")
            backend.imagesToPdf(images, out, ctx)
            io.emit(ctx, name, out)
        }
    }

    private fun pdfToImages(req: ApiRequest): Response {
        val backend = deps.tools.pdf ?: throw notImplemented("pdf")
        val body = req.jsonBody()
        val spec = ToolSpec.parse(deps, body, multi = false)
        val format = (body.string("format") ?: "png").lowercase().let { if (it == "jpg") "jpeg" else it }
        if (format != "png" && format != "jpeg") badRequest("format must be png or jpeg")
        val dpi = intField(body, "dpi", 72, 300) ?: 150
        return runJob("pdf.pdf2images", "PDF to images", spec) { ctx, io ->
            val input = io.stage(spec.items[0], 0)
            val files = backend.pdfToImages(input, io.outDir, format, dpi, ctx)
            files.forEach { io.emit(ctx, it.name, it) }
        }
    }

    private fun pdfToText(req: ApiRequest): Response {
        val backend = deps.tools.pdf ?: throw notImplemented("pdf")
        val spec = ToolSpec.parse(deps, req.jsonBody(), multi = false)
        return runJob("pdf.pdf2text", "PDF to text", spec) { ctx, io ->
            val item = spec.items[0]
            val out = File(io.outDir, "out.txt")
            backend.pdfToText(io.stage(item, 0), out, ctx)
            io.emit(ctx, FsPaths.splitExtension(item.name).first + ".txt", out)
        }
    }

    // ---- documents ----

    private fun docToPdf(req: ApiRequest): Response {
        val backend = deps.tools.doc ?: throw notImplemented("doc")
        val spec = ToolSpec.parse(deps, req.jsonBody(), multi = false)
        val item = spec.items[0]
        val kind = when (extOf(item.name)) {
            "md", "markdown" -> "md"
            "html", "htm" -> "html"
            "txt", "text", "log" -> "txt"
            else -> throw FsException(FsErrorCode.UNSUPPORTED, "only md, html and txt convert to PDF")
        }
        return runJob("doc.topdf", "Document to PDF", spec) { ctx, io ->
            val out = File(io.outDir, "out.pdf")
            backend.textToPdf(io.stage(item, 0), kind, out, ctx)
            io.emit(ctx, FsPaths.splitExtension(item.name).first + ".pdf", out)
        }
    }

    private fun officeToText(req: ApiRequest): Response {
        val backend = deps.tools.doc ?: throw notImplemented("doc")
        val body = req.jsonBody()
        val spec = ToolSpec.parse(deps, body, multi = false)
        val item = spec.items[0]
        if (extOf(item.name) != "docx" && extOf(item.name) != "odt") {
            throw FsException(FsErrorCode.UNSUPPORTED, "only docx and odt are supported")
        }
        val format = (body.string("format") ?: "txt").lowercase()
        if (format != "txt" && format != "html") badRequest("format must be txt or html")
        return runJob("doc.totext", "Document to $format", spec) { ctx, io ->
            val out = File(io.outDir, "out.$format")
            backend.officeToText(io.stage(item, 0), format, out)
            io.emit(ctx, FsPaths.splitExtension(item.name).first + "." + format, out)
        }
    }

    private fun csvJson(req: ApiRequest, toJson: Boolean): Response {
        val backend = deps.tools.doc ?: throw notImplemented("doc")
        val spec = ToolSpec.parse(deps, req.jsonBody(), multi = false)
        val item = spec.items[0]
        val ext = if (toJson) "json" else "csv"
        return runJob(if (toJson) "doc.csv2json" else "doc.json2csv", "Convert to $ext", spec) { ctx, io ->
            val out = File(io.outDir, "out.$ext")
            val input = io.stage(item, 0)
            if (toJson) backend.csvToJson(input, out) else backend.jsonToCsv(input, out)
            io.emit(ctx, FsPaths.splitExtension(item.name).first + "." + ext, out)
        }
    }

    // ---- media ----

    private fun mediaConvert(req: ApiRequest): Response {
        val backend = deps.tools.media?.takeIf { it.available } ?: throw notImplemented("media")
        val body = req.jsonBody()
        val spec = ToolSpec.parse(deps, body, multi = false)
        val preset = MediaTools.preset(body.string("preset"))
        return runJob("media.convert", "Convert media", spec) { ctx, io ->
            val item = spec.items[0]
            val out = File(io.outDir, "out.${preset.ext}")
            backend.convert(io.stage(item, 0), out, preset.id, ctx)
            io.emit(ctx, FsPaths.splitExtension(item.name).first + "." + preset.ext, out)
        }
    }

    private fun mediaTrim(req: ApiRequest): Response {
        val backend = deps.tools.media?.takeIf { it.available } ?: throw notImplemented("media")
        val body = req.jsonBody()
        val spec = ToolSpec.parse(deps, body, multi = false)
        val start = (body["startSec"] as? JsonPrimitive)?.doubleOrNull ?: badRequest("startSec must be a number")
        val end = (body["endSec"] as? JsonPrimitive)?.doubleOrNull ?: badRequest("endSec must be a number")
        MediaTools.validateTrim(start, end)
        val preset = body.string("preset")?.let { MediaTools.preset(it) }
        return runJob("media.trim", "Trim media", spec) { ctx, io ->
            val item = spec.items[0]
            val input = io.stage(item, 0)
            val base = FsPaths.splitExtension(item.name)
            val copyExt = base.second.removePrefix(".").ifEmpty { "bin" }
            val cut = File(io.outDir, "cut.$copyExt")
            backend.trim(input, cut, start, end, ctx)
            if (preset == null) {
                io.emit(ctx, base.first + "-trim." + copyExt, cut)
            } else {
                val out = File(io.outDir, "out.${preset.ext}")
                backend.convert(cut, out, preset.id, ctx)
                io.emit(ctx, base.first + "-trim." + preset.ext, out)
            }
        }
    }

    // ---- job plumbing ----

    private fun runJob(
        kind: String,
        title: String,
        spec: ToolSpec,
        body: (JobContext, JobIo) -> Unit,
    ): Response {
        val dst = deps.volumes.requireWritable(spec.dstVol)
        val id = deps.jobs.submit(kind, title) { ctx ->
            val staging = File(deps.cacheDir, "file_station_jobs/tools-${UUID.randomUUID()}")
            val outDir = File(staging, "out").also { it.mkdirs() }
            try {
                body(ctx, JobIo(spec, staging, outDir, dst))
            } finally {
                staging.deleteRecursively()
            }
        }
        return HttpResponses.json(buildJsonObject { put("jobId", id) }, HttpStatus.ACCEPTED)
    }

    private inner class JobIo(
        val spec: ToolSpec,
        val staging: File,
        val outDir: File,
        val dst: com.example.llamadroid.service.filestation.volume.FileStationVolume,
    ) {
        fun stage(item: ToolItem, index: Int): File = stage(item.vol, item.path, File(staging, "in$index"))

        /** Copies [file] into the destination volume as [name] following the conflict policy. */
        fun emit(ctx: JobContext, name: String, file: File) {
            ctx.checkCancelled()
            val safe = safeName(name)
            if (!dst.exists(spec.dstDir)) dst.mkdirs(spec.dstDir)
            val finalName = when (spec.conflict) {
                "skip" -> if (dst.exists(FsPaths.join(spec.dstDir, safe))) return else safe
                "overwrite" -> safe
                else -> FsPaths.conflictName(safe) { dst.exists(FsPaths.join(spec.dstDir, it)) }
            }
            val target = FsPaths.join(spec.dstDir, finalName)
            file.inputStream().use { input -> dst.openWrite(target).use { input.copyTo(it, 64 * 1024) } }
            ctx.addOutput(dst.id, target)
        }
    }

    private fun com.example.llamadroid.service.filestation.volume.FileStationVolume.exists(path: String): Boolean =
        try { stat(path); true } catch (e: FsException) { if (e.code == FsErrorCode.NOT_FOUND) false else throw e }

    /** Copies a volume file into [dir] keeping its name, so backends can derive output names. */
    private fun stage(volId: String, path: String, dir: File): File {
        val vol = deps.volumeOrThrow(volId)
        dir.mkdirs()
        val target = File(dir, FsPaths.name(path))
        vol.openRead(path).use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
        return target
    }

    private fun safeName(name: String): String =
        FsPaths.validateSegment(name.replace('/', '_').replace('\\', '_'))

    private fun outName(requested: String?, default: String, ext: String): String {
        val raw = requested?.trim().orEmpty().ifEmpty { default }
        val name = if (raw.lowercase().endsWith(".$ext")) raw else "$raw.$ext"
        return FsPaths.validateSegment(name)
    }

    private fun intField(body: JsonObject, name: String, min: Int, max: Int): Int? {
        val v = body[name] as? JsonPrimitive ?: return null
        if (v.booleanOrNull != null) badRequest("$name must be a number")
        val n = v.content.toDoubleOrNull() ?: badRequest("$name must be a number")
        if (n < min || n > max) badRequest("$name must be $min..$max")
        return n.toInt()
    }

    private fun badRequest(message: String): Nothing =
        throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message)

    private fun notImplemented(tool: String): HttpError =
        HttpError(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", "$tool tools are not available on this device")

    private fun extOf(name: String): String = FsPaths.splitExtension(name).second.removePrefix(".").lowercase()

    private fun formatForName(name: String): String = when (extOf(name)) {
        "png" -> "png"
        "webp" -> "webp"
        else -> "jpeg"
    }

    private fun extFor(format: String): String = if (format == "jpeg") "jpg" else format

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    companion object {
        const val MAX_QR_CHARS = 2000
        const val MAX_DIM = 16384
        const val MAX_ITEMS = 200
        private const val MAX_THUMB_SOURCE = 100L shl 20
        private const val MAX_THUMB_CACHE = 64L shl 20
        private val IMAGE_FORMATS = setOf("jpeg", "png", "webp")
        private val RANGES = Regex("^\\d+(-\\d+)?(,\\d+(-\\d+)?)*$")
    }
}

internal class ToolItem(val vol: String, val path: String) {
    val name: String get() = FsPaths.name(path)
}

/** Parsed common body: validated inputs (existing files) and the writable destination. */
internal class ToolSpec(
    val items: List<ToolItem>,
    val dstVol: String,
    val dstDir: String,
    val conflict: String,
) {
    companion object {
        fun parse(deps: FileStationDeps, body: JsonObject, multi: Boolean): ToolSpec {
            val raw = mutableListOf<JsonObject>()
            val single = body["item"]
            if (single != null) raw.add(single.asObject())
            if (raw.isEmpty() || multi) {
                if (body["items"] != null) body.array("items").forEach { raw.add(it.asObject()) }
            }
            if (raw.isEmpty()) throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "missing field: items")
            if (raw.size > ToolsApi.MAX_ITEMS) throw HttpError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "too many items")
            val items = (if (multi) raw else raw.take(1)).map { o ->
                val vol = o.requireString("vol")
                val path = FsPaths.normalize(o.string("path").orEmpty())
                val v = deps.volumes.get(vol) ?: throw FsException(FsErrorCode.NOT_FOUND, "unknown volume: $vol")
                val entry = v.stat(path)
                if (entry.isDir || path.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "not a file: $path")
                ToolItem(vol, path)
            }
            val first = items[0]
            val dstVol = body.string("dstVol") ?: first.vol
            val dstDir = if (body.string("dstVol") != null || body.string("dstDir") != null) {
                FsPaths.normalize(body.string("dstDir").orEmpty())
            } else FsPaths.parent(first.path)
            val conflict = when (val c = body.string("conflict")?.lowercase()) {
                null, "", "rename" -> "rename"
                "overwrite", "skip" -> c
                else -> throw FsException(FsErrorCode.INVALID_PATH, "unknown conflict policy: $c")
            }
            if (FsPaths.isHiddenName(FsPaths.name(dstDir)) || dstDir.split('/').any { FsPaths.isHiddenName(it) }) {
                throw FsException(FsErrorCode.INVALID_PATH, "hidden destination")
            }
            return ToolSpec(items, dstVol, dstDir, conflict)
        }
    }
}
