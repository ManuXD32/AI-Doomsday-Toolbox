package com.example.llamadroid.service

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@RunWith(RobolectricTestRunner::class)
class LlamaServerUsageStoreTest {
    private fun store() = LlamaServerUsageStore(RuntimeEnvironment.getApplication(), "test:${UUID.randomUUID()}")

    @Test fun `published idle checkpoint survives a new state store instance`() {
        val context = RuntimeEnvironment.getApplication()
        val states = LlamaServerSessionStateStore(context)
        val session = "test:${UUID.randomUUID()}"
        val original = LlamaServerSessionSnapshot(sessionId = session,
            status = LlamaServerSessionStatus.RUNNING, idleActivityAtElapsedMs = 42_000,
            idleActivityFingerprint = "tokens:25")
        try {
            states.write(original)
            assertEquals(original, LlamaServerSessionStateStore(context).readAll().first { it.sessionId == session })
        } finally { states.delete(session) }
    }

    @Test fun `abandoned caller expires and starts inactivity at lease expiry`() {
        val store = store()
        store.write("caller", true, 1000)
        assertTrue(store.read(61_000).active)
        val expired = store.read(61_001)
        assertFalse(expired.active)
        assertEquals(61_000L, expired.lastActivityMs)
        val policy = LlamaServerIdlePolicy(600_000, 0)
        val idle = LlamaServerIdleObservation(false, "0")
        assertFalse(policy.shouldStop(661_000, expired, idle))
        assertTrue(policy.shouldStop(661_001, expired, idle))
    }

    @Test fun `completed requests restart inactivity while another caller remains protected`() {
        val store = store()
        store.write("first", true, 1000)
        store.write("second", true, 30_000)
        store.write("first", false, 50_000)
        assertTrue(store.read(60_000).active)
        store.write("second", false, 65_000)
        assertFalse(store.read(65_001).active)
        assertEquals(65_000L, store.read(65_001).lastActivityMs)
    }

    @Test fun `receipts from a previous boot cannot hold the new process`() {
        val store = store()
        store.write("old", true, 900_000)
        assertEquals(LlamaServerUsageSnapshot(), store.read(1000))
    }

    @Test fun `new requests cannot acquire usage inside final idle-stop check`() = runBlocking {
        val store = store()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stop = async(Dispatchers.IO) {
            store.guardIdleStop { entered.complete(Unit); release.await() }
        }
        withTimeout(5000) { entered.await() }
        val request = async(Dispatchers.IO) { store.write("new-caller", true, 1000) }
        try {
            delay(25)
            assertFalse(request.isCompleted)
        } finally { release.complete(Unit) }
        withTimeout(5000) { stop.await(); request.await() }
        assertTrue(store.read(1001).active)
    }

    @Test fun `advanced easy templates retain loopback and probes`() {
        assertEquals(listOf("llama-server", "--model", "chat.gguf", "--host", "127.0.0.1", "--slots", "--metrics"),
            normalizeEasyLlamaServerArgs(listOf("llama-server", "--host=0.0.0.0", "--no-slots", "--model", "chat.gguf", "-H", "::", "--metrics")))
    }
}
