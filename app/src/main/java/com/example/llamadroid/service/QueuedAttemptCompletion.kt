package com.example.llamadroid.service

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Delivers a queue result from Job completion, even when the worker never enters its body. */
internal class QueuedAttemptCompletion(
    private val callback: ((QueuedGenerationOutcome) -> Unit)?
) {
    private val outcome = AtomicReference<QueuedGenerationOutcome?>(null)
    private val delivered = AtomicBoolean(false)

    fun record(result: QueuedGenerationOutcome) {
        outcome.compareAndSet(null, result)
    }

    fun complete(fallback: QueuedGenerationOutcome) {
        if (!delivered.compareAndSet(false, true)) return
        val result = outcome.get() ?: fallback
        callback?.let { sink -> runCatching { sink(result) } }
    }
}

internal fun queuedFallback(
    cause: Throwable?,
    timedOut: Boolean,
    timeoutMessage: String,
    explicitStopMessage: String,
    interruptedMessage: String
): QueuedGenerationOutcome = when {
    timedOut || cause is MediaProcessingForegroundTimeoutCancellation ->
        QueuedGenerationOutcome.interrupted(timeoutMessage)
    cause is CancellationException && cause.message == explicitStopMessage ->
        QueuedGenerationOutcome.stopped()
    else -> QueuedGenerationOutcome.interrupted(interruptedMessage)
}
