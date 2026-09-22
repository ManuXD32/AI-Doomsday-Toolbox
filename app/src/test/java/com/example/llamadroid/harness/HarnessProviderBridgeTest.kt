package com.example.llamadroid.harness

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

class HarnessProviderBridgeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    private val invocations = AtomicInteger()
    private val bridge = HarnessAndroidBridge("provider-cancellation", scope,
        handler = { _, _, _ -> invocations.incrementAndGet(); JSONObject().put("value", true) },
        modelStream = { request, emit ->
            invocations.incrementAndGet()
            try {
                emit(JSONObject("""{"id":"fixture","model":"fixture","choices":[{"index":0,"delta":{"content":"ready"}}]}"""))
                started.countDown()
                if (request.optString("model") == "wait") awaitCancellation()
            } finally { stopped.countDown() }
        })
    private val address = bridge.startBridge()
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @After fun close() {
        bridge.stopBridge(); scope.cancel()
        client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
    }

    @Test fun explicitCancellationStopsIdleProviderWithoutWaitingForAnotherToken() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<okhttp3.Response> { model("idle-stream", "wait", true) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val stream = pending.get(3, TimeUnit.SECONDS)
            stream.use {
                assertEquals(200, it.code)
                cancel("idle-stream")
                assertTrue("An idle provider kept running after explicit cancellation", stopped.await(3, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun cancellationAlsoStopsNonStreamingProvider() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<okhttp3.Response> { model("idle-json", "wait", false) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            cancel("idle-json")
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            pending.get(3, TimeUnit.SECONDS).use { assertEquals(500, it.code) }
        } finally { executor.shutdownNow() }
    }

    @Test fun cancellationBeforeAdmissionRejectsBothProviderAndToolWork() {
        cancel("before-provider")
        model("before-provider", "fixture", true).use { assertEquals(409, it.code) }
        cancel("before-tool")
        rpc(JSONObject().put("version", 1).put("id", "before-tool").put("method", "test.operation").put("args", JSONObject())).use {
            val result = JSONObject(requireNotNull(it.body).string())
            assertFalse(result.getBoolean("ok"))
            assertEquals("OPERATION_CANCELLED", result.getJSONObject("error").getString("code"))
        }
        assertEquals(0, invocations.get())
    }

    @Test fun completedProviderRequestIdCannotLaunchASecondInference() {
        model("one-inference", "fixture", false).use { assertEquals(200, it.code); requireNotNull(it.body).string() }
        model("one-inference", "fixture", false).use { assertEquals(409, it.code) }
        assertEquals(1, invocations.get())
    }

    private fun cancel(id: String) {
        rpc(JSONObject().put("version", 1).put("id", "cancel-$id").put("method", "request.cancel")
            .put("args", JSONObject().put("id", id))).use { assertEquals(200, it.code) }
    }

    private fun model(id: String, model: String, stream: Boolean) = client.newCall(
        request("/v1/chat/completions", JSONObject().put("model", model).put("stream", stream))
            .header("X-ADT-Request-Id", id).build()
    ).execute()

    private fun rpc(body: JSONObject) = client.newCall(request("/v1/rpc", body).build()).execute()
    private fun request(path: String, body: JSONObject) = Request.Builder().url(address.origin + path)
        .header("Authorization", "Bearer ${address.token}")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
}
