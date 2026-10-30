package com.example.llamadroid.tama.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sender side of the hand-off. Every call throws [IOException] when the peer is unreachable or the
 * connection dies; a logical refusal comes back as a reply object with a reason.
 */
open class PetTransferClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
    /** /hello blocks until the receiving user answers, so it needs a long read timeout. */
    private val helloReadTimeoutMs: Long = 150_000L
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun url(host: String, port: Int, path: String): String {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "http://$h:$port$path"
    }

    private fun Request.Builder.signed(headers: PairedAuthHeaders): Request.Builder =
        header(PairedAuth.HEADER_DEVICE, headers.deviceId)
            .header(PairedAuth.HEADER_TIMESTAMP, headers.timestamp.toString())
            .header(PairedAuth.HEADER_NONCE, headers.nonce)
            .header(PairedAuth.HEADER_SIGNATURE, headers.signature)

    // ---- Paired-device calls (no 6-digit code) ----

    /** Raw answer of a `/link/...` call; [signature] holds the reply's signature headers when present. */
    class LinkHttp(val code: Int, val body: String, val signature: PairedAuthHeaders?, val nonce: String)

    /** Signed JSON POST with short timeouts (reachability probes, reclaim). [nonce] ties a signed reply to this request. */
    open suspend fun linkPost(
        host: String, port: Int, path: String, body: String, signer: PairedSigner,
        connectTimeoutMs: Long = 3_000L, readTimeoutMs: Long = 8_000L
    ): LinkHttp {
        val nonce = java.util.UUID.randomUUID().toString()
        val bytes = body.toByteArray(Charsets.UTF_8)
        val request = Request.Builder().url(url(host, port, path))
            .signed(signer.headers("POST", path, PairedAuth.sha256Hex(bytes), nonce))
            .post(bytes.toRequestBody(JSON))
            .build()
        val client = http.newBuilder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return client.execute(request).use { r ->
            val sig = r.header(PairedAuth.HEADER_SIGNATURE)?.let { sigValue ->
                val ts = r.header(PairedAuth.HEADER_TIMESTAMP)?.toLongOrNull() ?: return@let null
                PairedAuthHeaders(
                    r.header(PairedAuth.HEADER_DEVICE).orEmpty(), ts, r.header(PairedAuth.HEADER_NONCE).orEmpty(), sigValue
                )
            }
            LinkHttp(r.code, r.body?.string().orEmpty(), sig, nonce)
        }
    }

    /** `/hello` authenticated by the pairing key instead of the code. */
    open suspend fun helloPaired(host: String, port: Int, hello: Hello, signer: PairedSigner): HelloReply {
        val text = json.encodeToString(hello)
        val request = Request.Builder().url(url(host, port, "/hello"))
            .signed(signer.headers("POST", "/hello", PairedAuth.sha256Hex(text.toByteArray(Charsets.UTF_8))))
            .post(text.toRequestBody(JSON))
            .build()
        val client = http.newBuilder().readTimeout(helloReadTimeoutMs, TimeUnit.MILLISECONDS).build()
        return decode(client.execute(request))
    }

    open suspend fun uploadPaired(
        host: String, port: Int, transferId: String, signer: PairedSigner, file: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): StagedReply {
        val request = Request.Builder().url(url(host, port, "/upload"))
            .header("X-Transfer-Id", transferId)
            .signed(signer.headers("PUT", "/upload", PairedSigner.transferBodyHash(transferId)))
            .put(ProgressFileBody(file, onProgress))
            .build()
        val client = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        return decode(client.execute(request))
    }

    open suspend fun commitPaired(host: String, port: Int, transferId: String, signer: PairedSigner): CommitReply {
        val request = Request.Builder().url(url(host, port, "/commit"))
            .header("X-Transfer-Id", transferId)
            .signed(signer.headers("POST", "/commit", PairedSigner.transferBodyHash(transferId)))
            .post(ByteArray(0).toRequestBody(JSON))
            .build()
        val client = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
        return decode(client.execute(request))
    }

    open suspend fun hello(host: String, port: Int, hello: Hello): HelloReply {
        val body = json.encodeToString(hello).toRequestBody(JSON)
        val request = Request.Builder().url(url(host, port, "/hello")).post(body).build()
        val client = http.newBuilder().readTimeout(helloReadTimeoutMs, TimeUnit.MILLISECONDS).build()
        return decode(client.execute(request))
    }

    open suspend fun upload(
        host: String, port: Int, transferId: String, code: String, file: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): StagedReply {
        val request = Request.Builder().url(url(host, port, "/upload"))
            .header("X-Transfer-Id", transferId)
            .header("X-Adt-Auth", PetTransferAuth.proof(transferId, code))
            .put(ProgressFileBody(file, onProgress))
            .build()
        val client = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        return decode(client.execute(request))
    }

    open suspend fun commit(host: String, port: Int, transferId: String, code: String): CommitReply {
        val request = Request.Builder().url(url(host, port, "/commit"))
            .header("X-Transfer-Id", transferId)
            .header("X-Adt-Auth", PetTransferAuth.proof(transferId, code))
            .post(ByteArray(0).toRequestBody(JSON))
            .build()
        val client = http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
        return decode(client.execute(request))
    }

    open suspend fun status(host: String, port: Int, transferId: String): StatusReply {
        val request = Request.Builder().url(url(host, port, "/status/$transferId")).get().build()
        return decode(http.execute(request))
    }

    private inline fun <reified T> decode(response: Response): T = response.use {
        val text = it.body?.string().orEmpty()
        try {
            json.decodeFromString<T>(text)
        } catch (e: Exception) {
            throw IOException("Unexpected reply (HTTP ${it.code})")
        }
    }

    private suspend fun OkHttpClient.execute(request: Request): Response =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val call = newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (cont.isActive) cont.resume(response) else response.close()
                    }
                })
            }
        }

    private class ProgressFileBody(
        private val file: File,
        private val onProgress: (Long, Long) -> Unit
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = file.length()
            var sent = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                    sent += n
                    onProgress(sent, total)
                }
            }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
