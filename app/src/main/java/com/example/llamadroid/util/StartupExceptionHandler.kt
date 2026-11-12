package com.example.llamadroid.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Handler for long-lived startup/background scopes: logs the error class and a bounded message
 * and swallows it so a stray failure cannot crash the app process. [log] is injectable for tests.
 */
fun startupExceptionHandler(
    tag: String,
    log: (String) -> Unit = { DebugLog.log(it) }
): CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
    if (error is CancellationException) return@CoroutineExceptionHandler
    runCatching {
        log("[$tag] uncaught ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}")
    }
}
