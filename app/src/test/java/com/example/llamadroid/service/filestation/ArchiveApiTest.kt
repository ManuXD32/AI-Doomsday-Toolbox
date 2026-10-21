package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.api.ArchiveApi
import com.example.llamadroid.service.filestation.http.FileStationDeps
import com.example.llamadroid.service.filestation.http.FileStationHttpServer
import com.example.llamadroid.service.filestation.http.JobInfo
import com.example.llamadroid.service.filestation.http.JobState
import com.example.llamadroid.service.filestation.http.PasswordVerifier
import com.example.llamadroid.service.filestation.jobs.JobManager
import com.example.llamadroid.service.filestation.volume.LocalFileVolume
import com.example.llamadroid.service.filestation.volume.VolumeRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.Files
import java.util.Random
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArchiveApiTest {
    private lateinit var root: File
    private lateinit var jobs: JobManager
    private lateinit var server: FileStationHttpServer
    private lateinit var a: File
    private lateinit var b: File
    private lateinit var ro: File
    private var port = 0

    @Before fun setUp() {
        root = Files.createTempDirectory("fs-archapi").toFile()
        a = File(root, "a").also { it.mkdirs() }
        b = File(root, "b").also { it.mkdirs() }
        ro = File(root, "ro").also { it.mkdirs() }
        val registry = VolumeRegistry(
            listOf(LocalFileVolume("a", "A", a), LocalFileVolume("b", "B", b), LocalFileVolume("ro", "RO", ro, readOnly = true)),
        )
        jobs = JobManager(File(root, "cache/file_station_jobs"))
        val deps = FileStationDeps(
            volumes = registry, auth = FakeAuth(), assets = MapAssets(emptyMap()), jobs = jobs,
            cacheDir = File(root, "cache"),
        )
        server = FileStationHttpServer("127.0.0.1", 0, deps, PasswordVerifier { pw, c -> pw == c.hash }, listOf(ArchiveApi(deps)))
        server.startServer()
        port = server.listeningPort
    }

    @After fun tearDown() {
        server.stop()
        jobs.shutdown()
        root.deleteRecursively()
    }

    private class R(val code: Int, val body: ByteArray, val type: String?, val disposition: String?) {
        val text get() = String(body)
    }

    private fun call(method: String, path: String, json: String? = null): R {
        val c = java.net.URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        if (method != "GET") c.setRequestProperty("X-FileStation", "1")
        if (json != null) {
            val bytes = json.toByteArray()
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        return R(code, stream?.use { it.readBytes() } ?: ByteArray(0), c.contentType, c.getHeaderField("Content-Disposition"))
    }

    private fun write(rel: String, bytes: ByteArray) {
        File(a, rel).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
    }

    private fun jobOf(r: R): JobInfo {
        assertEquals(r.text, 202, r.code)
        val id = Json.parseToJsonElement(r.text).jsonObject["jobId"]!!.jsonPrimitive.content
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val info = jobs.get(id)!!
            if (info.state != JobState.QUEUED && info.state != JobState.RUNNING) return info
            Thread.sleep(10)
        }
        throw AssertionError("job timeout")
    }

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() }
        }
        return out
    }

    @Test fun zipDownloadStreamsFolderLargerThanPipeBuffer() {
        val big = ByteArray(3_000_000).also { Random(1).nextBytes(it) }
        write("docs/big.bin", big)
        write("docs/sub/t.txt", "text".toByteArray())
        val r = call("GET", "/api/zip?vol=a&path=docs")
        assertEquals(200, r.code)
        assertEquals("application/zip", r.type)
        assertTrue(r.disposition!!.contains("docs.zip"))
        val entries = zipEntries(r.body)
        assertArrayEquals(big, entries["docs/big.bin"])
        assertEquals("text", String(entries["docs/sub/t.txt"]!!))
        assertTrue(entries.containsKey("docs/sub/"))
    }

    @Test fun zipDownloadMultiSelectionAndErrors() {
        write("one.txt", "1".toByteArray())
        write("two.txt", "2".toByteArray())
        val r = call("GET", "/api/zip?vol=a&path=one.txt&path=two.txt")
        assertEquals(200, r.code)
        assertEquals(setOf("one.txt", "two.txt"), zipEntries(r.body).keys)
        assertTrue(r.disposition!!.contains("selection.zip"))
        assertEquals(404, call("GET", "/api/zip?vol=a&path=missing").code)
        assertEquals(404, call("GET", "/api/zip?vol=zzz").code)
        assertEquals(400, call("GET", "/api/zip?vol=a&path=..%2Fx").code)
    }

    @Test fun zipJobWritesToVolume() {
        write("f/a.txt", "aa".toByteArray())
        val info = jobOf(call("POST", "/api/archive/zip",
            """{"items":[{"vol":"a","path":"f"}],"dstVol":"b","dstDir":"","name":"bundle"}"""))
        assertEquals(info.message, JobState.DONE, info.state)
        assertEquals("bundle.zip", info.outputs.single().path)
        assertEquals("b", info.outputs.single().vol)
        assertEquals("aa", String(zipEntries(File(b, "bundle.zip").readBytes())["f/a.txt"]!!))
    }

    @Test fun writesToReadOnlyVolumeAreForbidden() {
        write("f/a.txt", "aa".toByteArray())
        val z = call("POST", "/api/archive/zip", """{"items":[{"vol":"a","path":"f"}],"dstVol":"ro","dstDir":"","name":"x"}""")
        assertEquals(403, z.code)
        assertTrue(z.text, z.text.contains("READ_ONLY"))
        write("p.zip", byteArrayOf(0x50, 0x4b, 5, 6) + ByteArray(18))
        val e = call("POST", "/api/archive/extract", """{"vol":"a","path":"p.zip","dstVol":"ro","dstDir":""}""")
        assertEquals(403, e.code)
        assertTrue(ro.list()!!.isEmpty())
    }

    @Test fun extractJobAndMaliciousArchiveFails() {
        val good = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(good).use { z -> z.putNextEntry(java.util.zip.ZipEntry("in/g.txt")); z.write("g".toByteArray()); z.closeEntry() }
        write("good.zip", good.toByteArray())
        val ok = jobOf(call("POST", "/api/archive/extract", """{"vol":"a","path":"good.zip","dstVol":"b","dstDir":"","conflict":"rename"}"""))
        assertEquals(ok.message, JobState.DONE, ok.state)
        assertEquals("good", ok.outputs.single().path)
        assertEquals("g", File(b, "good/in/g.txt").readText())

        val bad = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bad).use { z -> z.putNextEntry(java.util.zip.ZipEntry("../evil")); z.write("e".toByteArray()); z.closeEntry() }
        write("bad.zip", bad.toByteArray())
        val failed = jobOf(call("POST", "/api/archive/extract", """{"vol":"a","path":"bad.zip","dstVol":"b","dstDir":""}"""))
        assertEquals(JobState.FAILED, failed.state)
        assertEquals("INVALID_PATH", failed.errorCode)
        assertEquals(listOf("good"), b.list()!!.toList())
        assertEquals(415, call("POST", "/api/archive/extract", """{"vol":"a","path":"good.zip.txt","dstVol":"b","dstDir":""}""").code)
    }

    @Test fun sha256AndDuplicatesJobsCarryResultJson() {
        write("x/one.txt", "abc".toByteArray())
        write("y/two.txt", "abc".toByteArray())
        write("y/three.txt", "other".toByteArray())
        val sha = jobOf(call("POST", "/api/tools/sha256", """{"items":[{"vol":"a","path":"x/one.txt"}]}"""))
        assertEquals(JobState.DONE, sha.state)
        val first = Json.parseToJsonElement(sha.resultJson!!).jsonObject["files"]!!.jsonArray[0].jsonObject
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", first["sha256"]!!.jsonPrimitive.content)

        val dup = jobOf(call("POST", "/api/tools/duplicates", """{"vol":"a","path":""}"""))
        assertEquals(JobState.DONE, dup.state)
        val groups = Json.parseToJsonElement(dup.resultJson!!).jsonObject["groups"]!!.jsonArray
        assertEquals(1, groups.size)
        assertEquals(listOf("x/one.txt", "y/two.txt"), groups[0].jsonObject["paths"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
