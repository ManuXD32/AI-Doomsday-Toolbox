package com.example.llamadroid.service

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal class MediaProcessingForegroundTimeoutCancellation(
    val userMessage: String
) : CancellationException(userMessage)

internal class ForegroundTimeoutGate {
    private val handled = AtomicBoolean(false)

    fun tryEnter(): Boolean = handled.compareAndSet(false, true)
}

internal fun isMediaProcessingForegroundTimeout(
    fgsType: Int,
    mediaProcessingType: Int
): Boolean = mediaProcessingType != 0 && (fgsType and mediaProcessingType) == mediaProcessingType
