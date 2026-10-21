package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.api.ToolsApi
import com.example.llamadroid.service.filestation.http.DocBackend
import com.example.llamadroid.service.filestation.http.FileStationDeps
import com.example.llamadroid.service.filestation.http.FileStationHttpServer
import com.example.llamadroid.service.filestation.http.ImageBackend
import com.example.llamadroid.service.filestation.http.JobContext
import com.example.llamadroid.service.filestation.http.JobInfo
import com.example.llamadroid.service.filestation.http.JobRunner
import com.example.llamadroid.service.filestation.http.MediaBackend
import com.example.llamadroid.service.filestation.http.PasswordVerifier
import com.example.llamadroid.service.filestation.http.PdfBackend
import com.example.llamadroid.service.filestation.http.QrBackend
import com.example.llamadroid.service.filestation.http.ToolBackends
import com.example.llamadroid.service.filestation.tools.CsvJson
import com.example.llamadroid.service.filestation.volume.LocalFileVolume
import com.example.llamadroid.service.filestation.volume.VolumeRegistry
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingJobs : JobRunner {
    val outputs = LinkedHashMap<String, MutableList<String>>()
    private var n = 0
    override fun submit(kind: String, title: String, body: (JobContext) -> Unit): String {
        val id = "job-${++n}"
        val list = mutableListOf<String>()
        outputs[id] = list
        body(object : JobContext {
            override val isCancelled = false
            override fun progress(fraction: Float, message: String?) {}
            override fun addOutput(vol: String, path: String) { list.add("$vol/$path") }
            override fun setResult(json: String) {}
            override fun checkCancelled() {}
        })
        return id
    }
    override fun cancel(id: String) = false
    override fun list(): List<JobInfo> = emptyList()
    override fun get(id: String): JobInfo? = null
}

private class FakeImage : ImageBackend {
    val thumbCalls = AtomicInteger()
    override fun convert(input: File, output: File, format: String, quality: Int, maxWidth: Int?, maxHeight: Int?) {
        output.writeText("IMG:${input.name}:$format:$quality:$maxWidth")
    }
    override fun thumbnail(input: File, size: Int): ByteArray? {
        thumbCalls.incrementAndGet()
        return if (input.extension == "png") "JPG$size".toByteArray() else null
    }
}

private class FakePdf : PdfBackend {
    override fun merge(inputs: List<File>, output: File, ctx: JobContext) { output.writeText("merged:${inputs.size}") }
    override fun split(input: File, ranges: String, outDir: File, ctx: JobContext): List<File> =
        ranges.split(',').map { r -> File(outDir, "${input.nameWithoutExtension}-p$r.pdf").also { it.writeText(r) } }
    override fun imagesToPdf(images: List<File>, output: File, ctx: JobContext) { output.writeText("i2p:${images.size}") }
    override fun pdfToImages(input: File, outDir: File, format: String, dpi: Int, ctx: JobContext): List<File> =
        listOf(File(outDir, "${input.nameWithoutExtension}-page-1.$format").also { it.writeText("$dpi") })
    override fun pdfToText(input: File, output: File, ctx: JobContext) { output.writeText("text") }
}

private class FakeDoc : DocBackend {
    override fun textToPdf(input: File, kind: String, output: File, ctx: JobContext) { output.writeText("pdf:$kind") }
    override fun officeToText(input: File, outputFormat: String, output: File) { output.writeText("office:$outputFormat") }
    override fun csvToJson(input: File, output: File) { output.writeText(CsvJson.csvToJson(input.readText())) }
    override fun jsonToCsv(input: File, output: File) { output.writeText(CsvJson.jsonToCsv(input.readText())) }
}

private class FakeMedia(override val available: Boolean = true) : MediaBackend {
    override fun convert(input: File, output: File, preset: String, ctx: JobContext) { output.writeText("media:$preset") }
    override fun trim(input: File, output: File, startSec: Double, endSec: Double, ctx: JobContext) {
        output.writeText("trim:$startSec-$endSec")
    }
}

class ToolsApiTest {
    private val root: File = Files.createTempDirectory("fs-tools").toFile()
    private val dirA = File(root, "a").also { it.mkdirs() }
    private val dirB = File(root, "b").also { it.mkdirs() }
    private val dirRo = File(root, "ro").also { it.mkdirs() }
    private val jobs = RecordingJobs()
    private val image = FakeImage()
    private var server: FileStationHttpServer? = null

    private fun start(tools: ToolBackends): Int {
        val registry = VolumeRegistry(
            listOf(LocalFileVolume("a", "A", dirA), LocalFileVolume("b", "B", dirB), LocalFileVolume("ro", "RO", dirRo, readOnly = true)),
        )
        val deps = FileStationDeps(
            volumes = registry, auth = FakeAuth(), assets = MapAssets(emptyMap()), jobs = jobs,
            tools = tools, cacheDir = File(root, "cache"),
        )
        val s = FileStationHttpServer("127.0.0.1", 0, deps, PasswordVerifier { pw, c -> pw == c.hash }, listOf(ToolsApi(deps)))
        s.startServer()
        server = s
        return s.listeningPort
    }

    private fun full() = ToolBackends(image, FakePdf(), FakeDoc(), FakeMedia(), object : QrBackend {
        override fun png(text: String, size: Int) = "PNG$size:$text".toByteArray()
    })

    @After fun stop() {
        server?.stop()
        root.deleteRecursively()
    }

    private fun call(port: Int, method: String, path: String, body: String? = null): Resp {
        val conn = java.net.URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        if (method != "GET") conn.setRequestProperty("X-FileStation", "1")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toByteArray()
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        return Resp(code, stream?.use { it.readBytes() } ?: ByteArray(0), conn.headerFields)
    }

    private fun code(r: Resp) = r.json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test fun `capabilities reflect backends`() {
        val p = start(ToolBackends())
        val none = call(p, "GET", "/api/tools/capabilities").json
        assertEquals("false", none["image"]!!.jsonPrimitive.content)
        assertEquals("false", none["media"]!!.jsonPrimitive.content)
        assertEquals(0, none["mediaPresets"]!!.jsonArray.size)
    }

    @Test fun `capabilities with all backends list presets`() {
        val p = start(full())
        val caps = call(p, "GET", "/api/tools/capabilities").json
        listOf("image", "pdf", "doc", "media", "qr").forEach { assertEquals("true", caps[it]!!.jsonPrimitive.content) }
        assertEquals(10, caps["mediaPresets"]!!.jsonArray.size)
        val unavailable = start(ToolBackends(media = FakeMedia(available = false)))
        assertEquals("false", call(unavailable, "GET", "/api/tools/capabilities").json["media"]!!.jsonPrimitive.content)
    }

    @Test fun `null backends answer 501`() {
        val p = start(ToolBackends(media = FakeMedia(available = false)))
        File(dirA, "p.png").writeText("x")
        val conv = call(p, "POST", "/api/tools/image/convert", """{"items":[{"vol":"a","path":"p.png"}],"format":"jpeg"}""")
        assertEquals(501, conv.code)
        assertEquals("NOT_IMPLEMENTED", code(conv))
        assertEquals(501, call(p, "GET", "/api/tools/qr?text=hi").code)
        assertEquals(501, call(p, "GET", "/thumb/a/p.png").code)
        assertEquals(501, call(p, "POST", "/api/tools/pdf/merge", "{}").code)
        assertEquals(501, call(p, "POST", "/api/tools/doc/topdf", "{}").code)
        assertEquals(501, call(p, "POST", "/api/tools/media/convert", "{}").code)
    }

    @Test fun `image convert job writes next to the source with conflict naming`() {
        val p = start(full())
        File(dirA, "pics").mkdirs()
        File(dirA, "pics/p.png").writeText("x")
        val body = """{"items":[{"vol":"a","path":"pics/p.png"}],"format":"jpeg","quality":70,"maxWidth":800}"""
        val r = call(p, "POST", "/api/tools/image/convert", body)
        assertEquals(202, r.code)
        assertEquals("job-1", r.json["jobId"]!!.jsonPrimitive.content)
        assertEquals(listOf("a/pics/p.jpg"), jobs.outputs["job-1"])
        assertEquals("IMG:p.png:jpeg:70:800", File(dirA, "pics/p.jpg").readText())
        call(p, "POST", "/api/tools/image/convert", body)
        assertEquals(listOf("a/pics/p (1).jpg"), jobs.outputs["job-2"])
        val skip = call(p, "POST", "/api/tools/image/convert", body.dropLast(1) + ""","conflict":"skip"}""")
        assertEquals(202, skip.code)
        assertTrue(jobs.outputs["job-3"]!!.isEmpty())
        assertFalse(File(dirA, "pics").list()!!.any { it.contains("(2)") })
    }

    @Test fun `output goes to an explicit writable volume and folder`() {
        val p = start(full())
        File(dirA, "t.csv").writeText("a,b\n1,2\n")
        val r = call(p, "POST", "/api/tools/doc/csv2json", """{"item":{"vol":"a","path":"t.csv"},"dstVol":"b","dstDir":"out/sub"}""")
        assertEquals(202, r.code)
        assertEquals(listOf("b/out/sub/t.json"), jobs.outputs["job-1"])
        assertTrue(File(dirB, "out/sub/t.json").readText().contains("\"a\": \"1\""))
        val back = call(p, "POST", "/api/tools/doc/json2csv", """{"item":{"vol":"b","path":"out/sub/t.json"},"dstVol":"a"}""")
        assertEquals(202, back.code)
        assertEquals("a,b\r\n1,2\r\n", File(dirA, "t (1).csv").readText())
    }

    @Test fun `read only destination is refused with 403`() {
        val p = start(full())
        File(dirRo, "p.png").writeText("x")
        File(dirA, "q.png").writeText("x")
        val ro = call(p, "POST", "/api/tools/image/convert", """{"items":[{"vol":"ro","path":"p.png"}],"format":"png"}""")
        assertEquals(403, ro.code)
        assertEquals("READ_ONLY", code(ro))
        val explicit = call(p, "POST", "/api/tools/image/convert", """{"items":[{"vol":"a","path":"q.png"}],"format":"png","dstVol":"ro","dstDir":""}""")
        assertEquals(403, explicit.code)
        // A read-only source is fine when the destination is writable.
        val ok = call(p, "POST", "/api/tools/image/convert", """{"items":[{"vol":"ro","path":"p.png"}],"format":"webp","dstVol":"a","dstDir":""}""")
        assertEquals(202, ok.code)
        assertTrue(File(dirA, "p.webp").isFile)
    }

    @Test fun `validation errors`() {
        val p = start(full())
        File(dirA, "p.png").writeText("x")
        File(dirA, "v.mp4").writeText("x")
        File(dirA, "d.pdf").writeText("x")
        val item = """{"vol":"a","path":"p.png"}"""
        assertEquals(400, call(p, "POST", "/api/tools/image/convert", """{"items":[$item]}""").code)
        assertEquals(400, call(p, "POST", "/api/tools/image/convert", """{"items":[$item],"format":"bmp"}""").code)
        assertEquals(400, call(p, "POST", "/api/tools/image/convert", """{"items":[$item],"format":"png","quality":0}""").code)
        assertEquals(400, call(p, "POST", "/api/tools/image/resize", """{"items":[$item]}""").code)
        assertEquals(404, call(p, "POST", "/api/tools/image/convert", """{"items":[{"vol":"a","path":"nope.png"}],"format":"png"}""").code)
        assertEquals(404, call(p, "POST", "/api/tools/image/convert", """{"items":[{"vol":"zz","path":"p.png"}],"format":"png"}""").code)
        assertEquals(400, call(p, "POST", "/api/tools/pdf/merge", """{"items":[{"vol":"a","path":"d.pdf"}]}""").code)
        assertEquals(400, call(p, "POST", "/api/tools/pdf/split", """{"item":{"vol":"a","path":"d.pdf"},"ranges":"1-x"}""").code)
        assertEquals(415, call(p, "POST", "/api/tools/doc/topdf", """{"item":$item}""").code)
        val v = """{"vol":"a","path":"v.mp4"}"""
        assertEquals(400, call(p, "POST", "/api/tools/media/convert", """{"item":$v,"preset":"nope"}""").code)
        assertEquals(400, call(p, "POST", "/api/tools/media/trim", """{"item":$v,"startSec":5,"endSec":2}""").code)
        assertEquals(400, call(p, "POST", "/api/tools/media/trim", """{"item":$v,"startSec":"x","endSec":2}""").code)
        assertEquals(202, call(p, "POST", "/api/tools/media/trim", """{"item":$v,"startSec":1,"endSec":2}""").code)
        assertTrue(File(dirA, "v-trim.mp4").isFile)
        assertEquals(202, call(p, "POST", "/api/tools/media/convert", """{"item":$v,"preset":"mp3-128"}""").code)
        assertEquals("media:mp3-128", File(dirA, "v.mp3").readText())
    }

    @Test fun `pdf split produces one output per range and merge needs two`() {
        val p = start(full())
        File(dirA, "d.pdf").writeText("x")
        File(dirA, "e.pdf").writeText("x")
        val s = call(p, "POST", "/api/tools/pdf/split", """{"item":{"vol":"a","path":"d.pdf"},"ranges":"1-3,5"}""")
        assertEquals(202, s.code)
        assertEquals(listOf("a/d-p1-3.pdf", "a/d-p5.pdf"), jobs.outputs["job-1"])
        val m = call(p, "POST", "/api/tools/pdf/merge", """{"items":[{"vol":"a","path":"d.pdf"},{"vol":"a","path":"e.pdf"}],"name":"all"}""")
        assertEquals(202, m.code)
        assertEquals("merged:2", File(dirA, "all.pdf").readText())
    }

    @Test fun `thumbnails are cached and unsupported files answer 415`() {
        val p = start(full())
        File(dirA, "p.png").writeText("x")
        File(dirA, "n.txt").writeText("x")
        val first = call(p, "GET", "/thumb/a/p.png?size=128")
        assertEquals(200, first.code)
        assertEquals("JPG128", first.text)
        assertEquals("image/jpeg", first.header("Content-Type"))
        val second = call(p, "GET", "/thumb/a/p.png?size=128")
        assertEquals("JPG128", second.text)
        assertEquals(1, image.thumbCalls.get())
        assertEquals(415, call(p, "GET", "/thumb/a/n.txt").code)
        assertEquals(404, call(p, "GET", "/thumb/a/missing.png").code)
        assertEquals(404, call(p, "GET", "/thumb/nope/p.png").code)
    }

    @Test fun `qr endpoint`() {
        val p = start(full())
        val ok = call(p, "GET", "/api/tools/qr?text=hello&size=9999")
        assertEquals(200, ok.code)
        assertEquals("PNG1024:hello", ok.text)
        assertEquals("PNG128:hello", call(p, "GET", "/api/tools/qr?text=hello&size=1").text)
        assertEquals(400, call(p, "GET", "/api/tools/qr?text=" + "x".repeat(2001)).code)
        assertEquals(400, call(p, "GET", "/api/tools/qr").code)
    }
}
