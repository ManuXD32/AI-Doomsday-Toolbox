package com.example.llamadroid.harness

import com.example.llamadroid.service.LiteRtLmWorkerCrashedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HarnessAndroidBridgeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls = AtomicInteger()
    private val entered = CountDownLatch(1)
    private val bridge = HarnessAndroidBridge("test-generation", scope, { method, session, args ->
        calls.incrementAndGet()
        if (method == "test.wait") { entered.countDown(); awaitCancellation() }
        JSONObject().put("session", session).put("number", args.optInt("number")).apply {
            if (method == "test.large") put("content", "x".repeat(70_000))
        }
    }, { _, emit ->
        emit(JSONObject("""{"id":"test-completion","created":1,"model":"test","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":"stop"}]}"""))
    })
    private val address = bridge.startBridge()
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @After fun close() { bridge.stopBridge(); scope.cancel(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }

    @Test fun authenticationAndBrowserOriginAreEnforced() {
        post("/v1/rpc", request("a"), token = "invalid").use {
            assertEquals(401, it.code)
            assertEquals("close", it.header("Connection"))
        }
        post("/v1/rpc", request("a"), origin = "http://127.0.0.1:1").use { assertEquals(403, it.code) }
        assertEquals(0, calls.get())
    }

    @Test fun repeatedMutationsRunOnceAndConflictingIdsAreRejected() {
        repeat(2) { post("/v1/rpc", request("same")).use { response ->
            assertEquals(200, response.code)
            assertTrue(JSONObject(requireNotNull(response.body).string()).getBoolean("ok"))
        } }
        val conflicting = request("same").put("args", JSONObject().put("number", 99))
        post("/v1/rpc", conflicting).use { assertEquals(409, it.code) }
        assertEquals(1, calls.get())
    }

    @Test fun cancellationReleasesRunningAndroidOperation() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<JSONObject> {
                post("/v1/rpc", request("waiting", "test.wait")).use { JSONObject(requireNotNull(it.body).string()) }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            post("/v1/rpc", request("cancel", "request.cancel").put("args", JSONObject().put("id", "waiting"))).close()
            val result = pending.get(3, TimeUnit.SECONDS)
            assertFalse(result.getBoolean("ok"))
            assertEquals("OPERATION_CANCELLED", result.getJSONObject("error").getString("code"))
        } finally { executor.shutdownNow() }
    }

    @Test fun largeRepliesLeaveSmallReceiptsWithoutReplayingTheOperation() {
        post("/v1/rpc", request("large", "test.large")).use {
            assertTrue(JSONObject(requireNotNull(it.body).string()).getBoolean("ok"))
        }
        post("/v1/rpc", request("large", "test.large")).use {
            val reply = JSONObject(requireNotNull(it.body).string())
            assertFalse(reply.getBoolean("ok"))
            assertEquals("BRIDGE_RESULT_NOT_RETAINED", reply.getJSONObject("error").getString("code"))
        }
        assertEquals(1, calls.get())
    }

    @Test fun nonStreamingProviderClientsReceiveJsonCompletion() {
        post("/v1/chat/completions", JSONObject().put("model", "test").put("stream", false)).use {
            assertEquals(200, it.code)
            val result = JSONObject(requireNotNull(it.body).string())
            assertEquals("chat.completion", result.getString("object"))
            assertEquals("Hi", result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        }
    }

    @Test fun wrappedLiteRtWorkerDisconnectHasARecoverableErrorCode() {
        val failing = HarnessAndroidBridge("worker-test", scope, { _, _, _ -> null }, { _, _ ->
            throw IllegalStateException("wrapped", LiteRtLmWorkerCrashedException(
                message = "worker stopped", requestId = "worker-test-request", workerLabel = "CPU",
                backendMode = "cpu", contextSize = 32768, mtpEnabled = true,
                lastPhase = "async message accepted", recentExit = null, elapsedMs = 4455,
            ))
        })
        val failingAddress = failing.startBridge()
        try {
            client.newCall(Request.Builder().url(failingAddress.origin + "/v1/chat/completions")
                .header("Authorization", "Bearer ${failingAddress.token}")
                .post(JSONObject().put("model", "test").put("stream", false).toString()
                    .toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
                assertEquals(500, response.code)
                val error = JSONObject(requireNotNull(response.body).string()).getJSONObject("error")
                assertEquals("LITERT_WORKER_CRASHED", error.getString("code"))
            }
        } finally {
            failing.stopBridge()
        }
    }

    private fun request(id: String, method: String = "test.value") = JSONObject().put("version", 1).put("id", id)
        .put("method", method).put("sessionId", "s1").put("args", JSONObject().put("number", 7))

    private fun post(path: String, value: JSONObject, token: String = address.token, origin: String? = null) = client.newCall(
        Request.Builder().url(address.origin + path).header("Authorization", "Bearer $token")
            .apply { if (origin != null) header("Origin", origin) }
            .post(value.toString().toRequestBody("application/json".toMediaType())).build()
    ).execute()
}
