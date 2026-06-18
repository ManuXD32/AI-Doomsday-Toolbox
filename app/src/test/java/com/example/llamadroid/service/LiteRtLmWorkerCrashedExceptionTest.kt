package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmWorkerCrashedExceptionTest {
    @Test
    fun `worker crash before engine initialization is cache purge eligible`() {
        val error = LiteRtLmWorkerCrashedException(
            message = "worker crashed",
            requestId = "req-1",
            workerLabel = "GPU",
            backendMode = "gpu",
            contextSize = 16000,
            mtpEnabled = false,
            lastPhase = "initializing Engine backend=GPU thread=DefaultDispatcher-worker-2",
            recentExit = "com.example:litert_lm: Reason 0 status=0",
            elapsedMs = 5456L
        )

        assertTrue(error.diedBeforeEngineInitialized)
        assertTrue(error.diagnosticDetail().contains("contextSize=16000"))
        assertTrue(error.diagnosticDetail().contains("mtp=false"))
        assertTrue(error.diagnosticDetail().contains("lastPhase=initializing Engine"))
    }

    @Test
    fun `worker crash after engine initialization is not startup cache purge eligible`() {
        val error = LiteRtLmWorkerCrashedException(
            message = "worker crashed",
            requestId = "req-2",
            workerLabel = "GPU",
            backendMode = "gpu",
            contextSize = 8192,
            mtpEnabled = true,
            lastPhase = "Engine initialized backend=GPU",
            recentExit = null,
            elapsedMs = 12000L
        )

        assertFalse(error.diedBeforeEngineInitialized)
    }
}
