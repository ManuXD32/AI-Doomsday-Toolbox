package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaRuntimeStateProjectionTest {
    @Test
    fun staleGenerationIsRejected() {
        val reducer = LlamaRuntimeProjectionReducer()

        assertTrue(reducer.accept(20L))
        assertFalse(reducer.accept(19L))
        assertEquals(20L, reducer.acceptedGeneration)
    }

    @Test
    fun persistedGenerationRehydratesOrderingGuard() {
        val reducer = LlamaRuntimeProjectionReducer()
        reducer.restore(40L)

        assertFalse(reducer.accept(39L))
        assertTrue(reducer.accept(40L))
        assertEquals(40L, reducer.acceptedGeneration)
    }

    @Test
    fun boundedRuntimeStateDecodesSafely() {
        assertEquals(ServerState.Running(8080), LlamaRuntimeStateProjection.decodeState("running:8080", ""))
        assertEquals(ServerState.Error("interrupted"), LlamaRuntimeStateProjection.decodeState("error", "interrupted"))
        assertEquals(ServerState.Error("Runtime state interrupted"), LlamaRuntimeStateProjection.decodeState("unknown", ""))
    }

    @Test
    fun runtimeLogsAreBoundedAndPathRedacted() {
        val sanitized = LlamaRuntimeStateProjection.sanitize("/private/model.gguf " + "x".repeat(600))

        assertTrue("<path>" in sanitized)
        assertTrue(sanitized.length <= 512)
    }

    @Test
    fun runtimeHeartbeatTimeoutOnlyInterruptsActiveRuntimeStates() {
        assertTrue(LlamaRuntimeStateProjection.runtimeHeartbeatTimedOut(ServerState.Running(8080), 36_000L, 0L))
        assertFalse(LlamaRuntimeStateProjection.runtimeHeartbeatTimedOut(ServerState.Error("failed"), 36_000L, 0L))
        assertFalse(LlamaRuntimeStateProjection.runtimeHeartbeatTimedOut(ServerState.Starting, 35_000L, 0L))
    }
}
