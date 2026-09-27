package com.example.llamadroid.service

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManagedLlamaServerPolicyTest {
    private val idle = LlamaServerIdleObservation(false, "tokens:0")

    @Test fun `recreation before the first idle probe preserves the readiness timestamp`() {
        val checkpoint = LlamaServerIdlePolicy(600_000, 1000).checkpoint()
        assertNull(checkpoint.fingerprint)
        val resumed = LlamaServerIdlePolicy(600_000,
            restoredLlamaIdleActivityAt(checkpoint.activityAtMs, 5000), checkpoint.fingerprint)
        assertFalse(resumed.shouldStop(601_000, LlamaServerUsageSnapshot(), idle))
        assertTrue(resumed.shouldStop(601_001, LlamaServerUsageSnapshot(), idle))
    }

    @Test fun `service recreation retains the idle boundary and detects external work while absent`() {
        val original = LlamaServerIdlePolicy(600_000, 1000)
        assertFalse(original.shouldStop(500_000, LlamaServerUsageSnapshot(), idle))
        val checkpoint = original.checkpoint()
        val resumed = LlamaServerIdlePolicy(600_000,
            restoredLlamaIdleActivityAt(checkpoint.activityAtMs, 600_999), checkpoint.fingerprint)
        assertFalse(resumed.shouldStop(601_000, LlamaServerUsageSnapshot(), idle))
        assertTrue(resumed.shouldStop(601_001, LlamaServerUsageSnapshot(), idle))

        val changed = LlamaServerIdlePolicy(600_000,
            restoredLlamaIdleActivityAt(checkpoint.activityAtMs, 700_000), checkpoint.fingerprint)
        val completedExternally = LlamaServerIdleObservation(false, "tokens:25")
        assertFalse(changed.shouldStop(700_000, LlamaServerUsageSnapshot(), completedExternally))
        assertFalse(changed.shouldStop(1_300_000, LlamaServerUsageSnapshot(), completedExternally))
        assertTrue(changed.shouldStop(1_300_001, LlamaServerUsageSnapshot(), completedExternally))
        assertEquals(1000L, restoredLlamaIdleActivityAt(null, 1000))
        assertEquals(1000L, restoredLlamaIdleActivityAt(9000, 1000))
    }

    @Test fun `idle shutdown is strictly after ten minutes`() {
        val policy = LlamaServerIdlePolicy(600_000, 1000)
        assertFalse(policy.shouldStop(601_000, LlamaServerUsageSnapshot(), idle))
        assertTrue(policy.shouldStop(601_001, LlamaServerUsageSnapshot(), idle))
    }

    @Test fun `active tool turn protects server even while no inference slot is busy`() {
        val policy = LlamaServerIdlePolicy(600_000, 0)
        assertFalse(policy.shouldStop(900_000, LlamaServerUsageSnapshot(active = true), idle))
        assertFalse(policy.shouldStop(1_500_000, LlamaServerUsageSnapshot(lastActivityMs = 900_000), idle))
        assertTrue(policy.shouldStop(1_500_001, LlamaServerUsageSnapshot(lastActivityMs = 900_000), idle))
    }

    @Test fun `external clients and changed token counters reset inactivity`() {
        val policy = LlamaServerIdlePolicy(600_000, 0)
        assertFalse(policy.shouldStop(0, LlamaServerUsageSnapshot(), idle))
        assertFalse(policy.shouldStop(601_000, LlamaServerUsageSnapshot(), LlamaServerIdleObservation(true, "tokens:1")))
        assertFalse(policy.shouldStop(900_000, LlamaServerUsageSnapshot(), LlamaServerIdleObservation(false, "tokens:2")))
        assertFalse(policy.shouldStop(1_500_000, LlamaServerUsageSnapshot(), LlamaServerIdleObservation(false, "tokens:2")))
        assertTrue(policy.shouldStop(1_500_001, LlamaServerUsageSnapshot(), LlamaServerIdleObservation(false, "tokens:2")))
    }

    @Test fun `health polling never renews inactivity and uncertainty cannot stop server`() {
        val policy = LlamaServerIdlePolicy(600_000, 0)
        repeat(60) { assertFalse(policy.shouldStop(it * 10_000L, LlamaServerUsageSnapshot(), idle)) }
        assertFalse(policy.shouldStop(700_000, LlamaServerUsageSnapshot(readable = false), idle))
        assertFalse(policy.shouldStop(700_000, LlamaServerUsageSnapshot(), null))
        assertTrue(policy.shouldStop(700_000, LlamaServerUsageSnapshot(), idle))
        assertFalse(LlamaServerIdlePolicy(0, 0).shouldStop(Long.MAX_VALUE, LlamaServerUsageSnapshot(), idle))
    }

    @Test fun `usable slot context wins over total or trained model context`() {
        val props = JSONObject("""{"n_ctx":32768,"n_ctx_train":131072,"default_generation_settings":{"n_ctx":8192}}""")
        assertEquals(4096, managedLlamaContextTokens(props, listOf(JSONObject("""{"n_ctx":4096}""")), 16384))
        assertEquals(8192, managedLlamaContextTokens(props, emptyList(), 16384))
        assertEquals(8192, managedLlamaContextTokens(JSONObject("""{"n_ctx":32768,"n_slots":4}"""), emptyList(), null))
        assertNull(managedLlamaContextTokens(JSONObject("""{"n_ctx_train":131072}"""), emptyList(), null))
        assertEquals(8192, configuredManagedContext(LlamaServerLaunchProfile(contextSize = 32768, parallel = 4)))
    }

    @Test fun `automatic ports exclude saved and occupied endpoints`() {
        val range = (49152..65535).toSet()
        assertEquals(55001, chooseEasyLlamaPort(range - 55001) { true })
        assertNull(chooseEasyLlamaPort(range) { true })
        assertNull(chooseEasyLlamaPort(emptySet()) { false })
    }

    @Test fun `old launch profiles never acquire easy-mode idle timeout`() {
        assertNull(LlamaServerLaunchProfile.decode("""{"modelPath":"old.gguf"}""")?.idleStopSeconds)
        val easy = LlamaServerLaunchProfile(modelPath = "easy.gguf", idleStopSeconds = 600)
        assertEquals(600, LlamaServerLaunchProfile.decode(LlamaServerLaunchProfile.encode(easy))?.idleStopSeconds)
    }
}
