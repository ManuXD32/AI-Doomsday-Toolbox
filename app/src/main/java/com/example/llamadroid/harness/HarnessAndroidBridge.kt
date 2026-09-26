package com.example.llamadroid.harness

import fi.iki.elonen.NanoHTTPD
import com.example.llamadroid.service.LiteRtLmWorkerCrashedException
import com.example.llamadroid.service.LiteRtPromptOverLimitException
import com.example.llamadroid.service.containsLiteRtPromptOverLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

data class HarnessBridgeAddress(val origin: String, val token: String, val generation: String)

/** Narrow authenticated loopback endpoint. No Android object is exposed to either WebView. */
class HarnessAndroidBridge(
    private val generation: String,
    private val scope: CoroutineScope,
    private val handler: suspend (method: String, sessionId: String?, args: JSONObject) -> Any?,
    private val modelStream: suspend (request: JSONObject, emit: suspend (JSONObject) -> Unit) -> Unit
) : NanoHTTPD("127.0.0.1", 0) {
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val requests = ConcurrentHashMap<String, Pending>()
    private val streams = ConcurrentHashMap<String, Pair<Job, PipedOutputStream>>()
    private val modelCalls = ConcurrentHashMap<String, Job>()
    // Small metadata-only receipts close the cancel-before-admission race and
    // prevent an ambiguous provider HTTP retry from starting inference twice.
    private val cancelledIds = linkedMapOf<String, Long>()
    private val modelReceipts = linkedMapOf<String, Long>()
    private val modelSlots = Semaphore(4)
    @Volatile private var accepting = false

    fun startBridge(): HarnessBridgeAddress {
        start(SOCKET_READ_TIMEOUT, false)
        accepting = true
        return HarnessBridgeAddress("http://127.0.0.1:$listeningPort", token, generation)
    }

    fun stopBridge() {
        synchronized(requests) {
            accepting = false
            requests.values.forEach { it.result.cancel() }
            requests.clear()
            streams.values.forEach { (job, output) -> job.cancel(); runCatching { output.close() } }
            streams.clear()
            modelCalls.values.forEach { it.cancel() }
            modelCalls.clear()
            cancelledIds.clear()
            modelReceipts.clear()
        }
        stop()
    }

    override fun serve(session: IHTTPSession): Response {
        if (!accepting) return json(Response.Status.SERVICE_UNAVAILABLE, failure("BRIDGE_STOPPING"))
        val authorization = session.headers["authorization"].orEmpty().toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(authorization, "Bearer $token".toByteArray(Charsets.UTF_8))) {
            return json(Response.Status.UNAUTHORIZED, failure("BRIDGE_AUTH_REQUIRED"))
        }
        // The bridge is a host-plugin protocol. Web pages, including plugin panels, use Harness RPC.
        if (session.headers.containsKey("origin")) return json(Response.Status.FORBIDDEN, failure("BRIDGE_ORIGIN_REJECTED"))
        if (session.method == Method.GET && session.uri == "/v1/models") {
            return modelCatalog()
        }
        if (session.method != Method.POST) return json(Response.Status.METHOD_NOT_ALLOWED, failure("BRIDGE_METHOD_REJECTED"))
        val length = session.headers["content-length"]?.toLongOrNull()
        val maximum = if (session.uri == "/v1/chat/completions") MAX_MODEL_REQUEST_BYTES else MAX_REQUEST_BYTES
        if (length == null || length !in 1..maximum) {
            return json(Response.Status.BAD_REQUEST, failure("BRIDGE_BODY_LIMIT"))
        }
        return try {
            val parts = mutableMapOf<String, String>()
            session.parseBody(parts)
            val body = parts["postData"].orEmpty()
            require(body.toByteArray(Charsets.UTF_8).size <= maximum)
            val request = JSONObject(body)
            when (session.uri) {
                "/v1/rpc" -> rpc(request, body)
                "/v1/chat/completions" -> {
                    val id = session.headers["x-adt-request-id"] ?: UUID.randomUUID().toString()
                    require(REQUEST_ID.matches(id))
                    if (request.optBoolean("stream")) stream(request, id) else completion(request, id)
                }
                else -> json(Response.Status.NOT_FOUND, failure("BRIDGE_ROUTE_UNKNOWN"))
            }
        } catch (_: Exception) { json(Response.Status.BAD_REQUEST, failure("BRIDGE_REQUEST_INVALID")) }
    }

    private fun rpc(request: JSONObject, body: String): Response {
        require(request.getInt("version") == 1) { "BRIDGE_VERSION_UNSUPPORTED" }
        val id = request.getString("id")
        require(REQUEST_ID.matches(id))
        val method = request.getString("method")
        val args = request.optJSONObject("args") ?: JSONObject()
        if (method == "request.cancel") {
            val target = args.getString("id")
            require(REQUEST_ID.matches(target))
            synchronized(requests) {
                trimReceipts()
                cancelledIds[target] = System.nanoTime()
                while (cancelledIds.size > MAX_CANCEL_RECEIPTS) cancelledIds.remove(cancelledIds.keys.first())
                requests[target]?.result?.cancel()
                modelCalls[target]?.cancel()
                streams[target]?.let { (job, output) -> job.cancel(); runCatching { output.close() } }
            }
            return json(Response.Status.OK, JSONObject().put("version", 1).put("id", id).put("ok", true))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).toList()
        val pending = synchronized(requests) {
            if (!accepting) return json(Response.Status.SERVICE_UNAVAILABLE, failure("BRIDGE_STOPPING"))
            trimReceipts()
            if (id in cancelledIds) return json(Response.Status.OK, failure("OPERATION_CANCELLED").put("id", id))
            if (id in modelReceipts) return json(Response.Status.CONFLICT, failure("BRIDGE_REQUEST_ID_REUSED"))
            val existing = requests[id]
            if (existing != null && existing.digest != digest) return json(Response.Status.CONFLICT, failure("BRIDGE_REQUEST_ID_REUSED"))
            existing ?: run {
                if (requests.size >= MAX_REQUESTS) {
                    requests.entries.filter { it.value.result.isCompleted }.take(MAX_REQUESTS / 2).forEach { requests.remove(it.key, it.value) }
                    if (requests.size >= MAX_REQUESTS) return json(Response.Status.SERVICE_UNAVAILABLE, failure("BRIDGE_BUSY"))
                }
                Pending(digest, scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    val sessionId = request.optString("sessionId").takeIf { it.isNotBlank() }
                    withTimeout(10 * 60_000L) { handler(method, sessionId, args) }
                }).also { requests[id] = it }
            }
        }
        return safely(id) { runBlocking {
            val value = pending.result.await()
            if ((value?.toString()?.length ?: 0) > 64 * 1024) {
                // Keep a receipt, not every file range or tool result returned to the host plugin.
                val receipt = CompletableDeferred<Any?>().apply {
                    completeExceptionally(IllegalStateException("BRIDGE_RESULT_NOT_RETAINED"))
                }
                requests.replace(id, pending, Pending(digest, receipt))
            }
            JSONObject().put("version", 1).put("id", id).put("ok", true).put("value", value ?: JSONObject.NULL)
        } }
    }

    private fun completion(request: JSONObject, id: String): Response {
        if (!modelSlots.tryAcquire()) return json(Response.Status.SERVICE_UNAVAILABLE, failure("BRIDGE_BUSY"))
        val operation = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val accumulator = HarnessCompletionAccumulator()
            modelStream(request) { accumulator.add(it) }
            accumulator.finish()
        }
        synchronized(requests) {
            val rejected = modelAdmissionFailure(id)
            if (rejected != null) {
                operation.cancel(); modelSlots.release()
                return rejected
            }
            modelReceipts[id] = System.nanoTime()
            modelCalls[id] = operation
        }
        return try { json(Response.Status.OK, runBlocking { operation.await() }) }
        catch (error: Exception) {
            val code = providerModelErrorCode(error)
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", JSONObject().put("code", code).put("message", code)))
        } finally { modelCalls.remove(id); operation.cancel(); modelSlots.release() }
    }

    private fun stream(request: JSONObject, id: String): Response {
        if (!modelSlots.tryAcquire()) return json(Response.Status.SERVICE_UNAVAILABLE, failure("BRIDGE_BUSY"))
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                modelStream(request) { event ->
                    output.write("data: $event\n\n".toByteArray(Charsets.UTF_8)); output.flush()
                }
                output.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8)); output.flush()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val code = providerModelErrorCode(error)
                val event = JSONObject().put("error", JSONObject().put("code", code).put("message", code))
                runCatching { output.write("data: $event\n\n".toByteArray(Charsets.UTF_8)) }
            } finally {
                runCatching { output.close() }
            }
        }
        // Completion handlers also run when cancellation wins before a lazy
        // coroutine enters its body; a finally block alone can leak a slot.
        val ownedStream = job to output
        job.invokeOnCompletion {
            runCatching { output.close() }
            streams.remove(id, ownedStream)
            modelSlots.release()
        }
        synchronized(requests) {
            val rejected = modelAdmissionFailure(id)
            if (rejected != null) {
                job.cancel(); output.close(); input.close()
                return rejected
            }
            modelReceipts[id] = System.nanoTime()
            streams[id] = ownedStream
            job.start()
        }
        val cancellable = object : FilterInputStream(input) {
            override fun close() {
                job.cancel()
                runCatching { output.close() }
                super.close()
            }
        }
        return newChunkedResponse(Response.Status.OK, "text/event-stream", cancellable).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-ADT-Bridge-Version", "1")
        }
    }

    private fun modelCatalog(): Response {
        if (!modelSlots.tryAcquire()) return json(Response.Status.SERVICE_UNAVAILABLE, failure("BRIDGE_BUSY"))
        val id = UUID.randomUUID().toString()
        val operation = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { handler("provider.models", null, JSONObject()) }
        synchronized(requests) {
            if (!accepting) {
                operation.cancel(); modelSlots.release()
                return json(Response.Status.SERVICE_UNAVAILABLE, failure("BRIDGE_STOPPING"))
            }
            modelCalls[id] = operation
        }
        return try { safely { runBlocking { operation.await() } } }
        finally { modelCalls.remove(id); operation.cancel(); modelSlots.release() }
    }

    /** Caller holds requests; admission and explicit cancellation share the same lock. */
    private fun modelAdmissionFailure(id: String): Response? {
        if (!accepting) return json(Response.Status.SERVICE_UNAVAILABLE, failure("BRIDGE_STOPPING"))
        trimReceipts()
        if (id in cancelledIds) return json(Response.Status.CONFLICT, failure("OPERATION_CANCELLED"))
        if (requests.containsKey(id) || id in modelReceipts || modelCalls.containsKey(id) || streams.containsKey(id)) {
            return json(Response.Status.CONFLICT, failure("BRIDGE_REQUEST_ID_REUSED"))
        }
        while (modelReceipts.size >= MAX_REQUESTS) {
            val oldestFinished = modelReceipts.keys.firstOrNull { !modelCalls.containsKey(it) && !streams.containsKey(it) }
                ?: return json(Response.Status.SERVICE_UNAVAILABLE, failure("BRIDGE_BUSY"))
            modelReceipts.remove(oldestFinished)
        }
        return null
    }

    private fun trimReceipts() {
        val cutoff = System.nanoTime() - RECEIPT_TTL_NANOS
        cancelledIds.entries.removeAll { it.value < cutoff }
        modelReceipts.entries.removeAll { it.value < cutoff && !modelCalls.containsKey(it.key) && !streams.containsKey(it.key) }
    }

    private inline fun safely(id: String? = null, operation: () -> Any?): Response = try {
        json(Response.Status.OK, operation())
    } catch (_: CancellationException) {
        json(Response.Status.OK, failure("OPERATION_CANCELLED").put("id", id))
    } catch (error: Exception) {
        val code = error.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{1,80}")) } ?: "BRIDGE_OPERATION_FAILED"
        json(Response.Status.OK, failure(code).put("id", id))
    }

    private fun json(status: Response.Status, value: Any?): Response = newFixedLengthResponse(
        status, "application/json; charset=utf-8", value?.toString() ?: "null"
    ).apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-ADT-Bridge-Version", "1")
        // Admission failures can leave an unread request body. Do not let a pooled connection
        // reinterpret that body as the next request's HTTP headers.
        if (status.requestStatus >= 400) closeConnection(true)
    }

    private fun failure(code: String) = JSONObject().put("version", 1).put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", code).put("details", JSONObject()))

    private fun providerModelErrorCode(error: Throwable): String {
        if (error.containsLiteRtPromptOverLimit()) return LiteRtPromptOverLimitException.CODE
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        var cause: Throwable? = error
        while (cause != null && seen.add(cause)) {
            if (cause is LiteRtLmWorkerCrashedException) return "LITERT_WORKER_CRASHED"
            cause = cause.cause
        }
        return "PROVIDER_INTERRUPTED"
    }
    private data class Pending(val digest: List<Byte>, val result: Deferred<Any?>)
    private companion object {
        const val MAX_REQUEST_BYTES = 1024L * 1024L
        const val MAX_MODEL_REQUEST_BYTES = 32L * 1024L * 1024L
        const val MAX_REQUESTS = 128
        const val MAX_CANCEL_RECEIPTS = 256
        const val RECEIPT_TTL_NANOS = 120_000_000_000L
        val REQUEST_ID = Regex("[A-Za-z0-9_.:-]{1,128}")
    }
}
