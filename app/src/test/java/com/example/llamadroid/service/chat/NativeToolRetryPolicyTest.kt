package com.example.llamadroid.service.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolRetryPolicyTest {
    @Test
    fun transientFailuresAreRetried() {
        listOf(
            "tool_error: SocketTimeoutException",
            "tool_error: Read timed out",
            "tool_error: Unable to resolve host \"example.org\"",
            "tool_error: UnknownHostException: example.org",
            "tool_error: Connection reset by peer",
            "tool_error: server busy, try again",
            "tool_error: HTTP 503 service unavailable",
            "tool_error: java.io.IOException: unexpected end of stream",
            "TOOL_ERROR: Failed to connect to localhost/127.0.0.1:8080"
        ).forEach { assertTrue(it, NativeToolRetryPolicy.shouldRetry(it)) }
    }

    @Test
    fun fixedRejectionsAndSuccessAreNeverRetried() {
        listOf(
            "tool_error: missing required argument 'path'",
            "tool_error: Invalid URL",
            "tool_error: URL blocked by the safety policy",
            "tool_error: unknown note id 42",
            "tool_error: permission denied",
            "tool_error: file not found: a.txt",
            "tool_error: tool is disabled in this chat",
            "tool_error: something odd happened",
            "tool_error: ",
            "all good, found 3 results",
            "Request timed out is a normal phrase in a successful result"
        ).forEach { assertFalse(it, NativeToolRetryPolicy.shouldRetry(it)) }
    }
}
