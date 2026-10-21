package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.http.AssetSource
import com.example.llamadroid.service.filestation.http.AuthConfigProvider
import com.example.llamadroid.service.filestation.http.FileStationDeps
import com.example.llamadroid.service.filestation.http.FileStationHttpServer
import com.example.llamadroid.service.filestation.http.JobContext
import com.example.llamadroid.service.filestation.http.JobInfo
import com.example.llamadroid.service.filestation.http.JobRunner
import com.example.llamadroid.service.filestation.http.PasswordCredentials
import com.example.llamadroid.service.filestation.http.PasswordVerifier
import com.example.llamadroid.service.filestation.volume.LocalFileVolume
import com.example.llamadroid.service.filestation.volume.VolumeRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class FakeAuth(
    @Volatile var creds: PasswordCredentials? = null,
    @Volatile var secret: ByteArray = ByteArray(32) { it.toByte() },
) : AuthConfigProvider {
    override fun credentials(): PasswordCredentials? = creds
    override fun sessionSecret(): ByteArray = secret
    override val lanVisible: Boolean = false
}

class MapAssets(private val files: Map<String, ByteArray>) : AssetSource {
    override fun open(path: String): InputStream? = files[path]?.let { ByteArrayInputStream(it) }
    override fun length(path: String): Long = files[path]?.size?.toLong() ?: -1
}

/** Runs job bodies inline; enough for the router tests. */
class InlineJobs : JobRunner {
    override fun submit(kind: String, title: String, body: (JobContext) -> Unit): String {
        body(object : JobContext {
            override val isCancelled = false
            override fun progress(fraction: Float, message: String?) {}
            override fun addOutput(vol: String, path: String) {}
            override fun setResult(json: String) {}
            override fun checkCancelled() {}
        })
        return "job-1"
    }
    override fun cancel(id: String) = false
    override fun list(): List<JobInfo> = emptyList()
    override fun get(id: String): JobInfo? = null
}

class Resp(val code: Int, val body: ByteArray, private val headers: Map<String?, List<String>>) {
    val text: String get() = String(body, Charsets.UTF_8)
    val json: JsonObject get() = Json.parseToJsonElement(text).jsonObject
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    fun headerValues(name: String): List<String> =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()
}

/** Boots the server on 127.0.0.1:0 with volumes "a" (rw), "b" (rw) and "ro" (read-only). */
class TestServerHarness(
    val auth: FakeAuth = FakeAuth(),
    assets: Map<String, ByteArray> = mapOf("index.html" to "<html>hi</html>".toByteArray()),
) {
    val root: File = Files.createTempDirectory("fs-router").toFile()
    val dirA = File(root, "a").also { it.mkdirs() }
    val dirB = File(root, "b").also { it.mkdirs() }
    val dirRo = File(root, "ro").also { it.mkdirs() }
    val registry = VolumeRegistry(
        listOf(
            LocalFileVolume("a", "A", dirA),
            LocalFileVolume("b", "B", dirB),
            LocalFileVolume("ro", "RO", dirRo, readOnly = true),
        ),
    )
    val server = FileStationHttpServer(
        "127.0.0.1", 0,
        FileStationDeps(
            volumes = registry, auth = auth, assets = MapAssets(assets), jobs = InlineJobs(),
            appVersionCode = 7, cacheDir = File(root, "cache"),
        ),
        PasswordVerifier { pw, c -> pw == c.hash },
    )
    val port: Int

    init {
        server.startServer()
        port = server.listeningPort
    }

    fun close() {
        server.stop()
        root.deleteRecursively()
    }

    fun call(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        csrf: Boolean = method != "GET" && method != "HEAD",
        cookie: String? = null,
    ): Resp {
        val conn = java.net.URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = false
        if (csrf) conn.setRequestProperty("X-FileStation", "1")
        if (cookie != null) conn.setRequestProperty("Cookie", cookie)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else runCatching { conn.inputStream }.getOrNull()
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        return Resp(code, bytes, conn.headerFields)
    }

    fun postJson(path: String, json: String, cookie: String? = null, headers: Map<String, String> = emptyMap()) =
        call("POST", path, json.toByteArray(), headers + ("Content-Type" to "application/json"), cookie = cookie)

    /** Sends a hand-written request (for restricted headers such as Origin and Host). */
    fun raw(request: String): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().write(request.toByteArray())
            s.getOutputStream().flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
}
