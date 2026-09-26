package com.example.llamadroid.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class QueuedAttemptCompletionTest {
    @Test fun `cancelled lazy worker completes queue without entering its body`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        var entered = false
        val outcomes = mutableListOf<QueuedGenerationOutcome>()
        val completion = QueuedAttemptCompletion { outcomes += it }
        val job = scope.launch(start = CoroutineStart.LAZY) { entered = true }
        job.invokeOnCompletion { cause ->
            completion.complete(queuedFallback(cause, false, "timeout", "stop", "interrupted"))
        }
        job.cancel(CancellationException("stop"))
        job.start()
        job.join()
        completion.complete(QueuedGenerationOutcome.failed("late"))
        assertFalse(entered)
        assertEquals(listOf("STOPPED"), outcomes.map { it.status })
        scope.cancel()
    }

    @Test fun `recorded terminal result wins over late fallback`() {
        val outcomes = mutableListOf<QueuedGenerationOutcome>()
        val completion = QueuedAttemptCompletion { outcomes += it }
        completion.record(QueuedGenerationOutcome.succeeded("output"))
        completion.record(QueuedGenerationOutcome.stopped())
        completion.complete(QueuedGenerationOutcome.interrupted("late"))
        assertEquals("SUCCEEDED", outcomes.single().status)
        assertEquals("output", outcomes.single().resultPath)
    }

    @Test fun `cancel after dispatch still delivers exactly one stopped result`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val hold = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<QueuedGenerationOutcome>()
        val completion = QueuedAttemptCompletion { outcomes += it }
        var entered = false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            entered = true
            hold.await()
        }
        job.invokeOnCompletion { cause ->
            completion.complete(queuedFallback(cause, false, "timeout", "stop", "interrupted"))
        }
        job.start()
        assertTrue(entered)
        job.cancel(CancellationException("stop"))
        job.join()
        completion.complete(QueuedGenerationOutcome.failed("late"))
        assertEquals(listOf("STOPPED"), outcomes.map { it.status })
        scope.cancel()
    }

    @Test fun `service interruption and timeout remain retryable`() {
        assertEquals("INTERRUPTED", queuedFallback(
            CancellationException("service destroyed"), false, "timeout", "stop", "interrupted"
        ).status)
        assertEquals("INTERRUPTED", queuedFallback(
            MediaProcessingForegroundTimeoutCancellation("timeout"), false,
            "timeout", "stop", "interrupted"
        ).status)
    }
}
