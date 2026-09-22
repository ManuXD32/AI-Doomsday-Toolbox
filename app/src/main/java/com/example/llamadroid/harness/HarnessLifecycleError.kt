package com.example.llamadroid.harness

import com.example.llamadroid.harness.runtime.HarnessRuntimeException

/** Retain the reviewed runtime code across coroutine/bridge exception wrapping. */
internal fun harnessLifecycleErrorCode(failure: Throwable): String {
    var cause: Throwable? = failure
    repeat(6) {
        val current = cause ?: return@repeat
        if (current is HarnessRuntimeException) return current.code
        cause = current.cause?.takeUnless { it === current }
    }
    val messageCode = failure.message?.let(::harnessDiagnosticErrorClass)
    if (messageCode != null && messageCode != "RemoteError") return messageCode
    val classCode = "HARNESS_${failure.javaClass.simpleName.uppercase(java.util.Locale.ROOT)}"
    return classCode.takeIf { harnessDiagnosticErrorClass(it) != "RemoteError" } ?: "HARNESS_START_FAILED"
}
