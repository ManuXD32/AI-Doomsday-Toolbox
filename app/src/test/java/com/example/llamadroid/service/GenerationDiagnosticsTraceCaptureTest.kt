package com.example.llamadroid.service

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationDiagnosticsTraceCaptureTest {
    @Test
    fun `trace capture streams through EOF and atomically retains the complete small trace`() {
        val directory = createTempDir(prefix = "exit-trace-")
        try {
            val bytes = "fatal signal 11\nbacktrace\n".repeat(500).toByteArray()
            val target = File(directory, "last_exit_trace.bin")

            val capture = captureExitTraceForDiagnostics(ByteArrayInputStream(bytes), target, maxBytes = bytes.size + 1)

            requireNotNull(capture)
            assertFalse(capture.truncated)
            assertEquals(bytes.size, capture.byteCount)
            assertEquals(bytes.toList(), target.readBytes().toList())
            assertEquals(sha256(bytes), capture.sha256)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `trace capture detects safety cap truncation with one extra byte`() {
        val directory = createTempDir(prefix = "exit-trace-")
        try {
            val bytes = ByteArray(65) { it.toByte() }
            val target = File(directory, "last_exit_trace.bin")

            val capture = captureExitTraceForDiagnostics(ByteArrayInputStream(bytes), target, maxBytes = 64)

            requireNotNull(capture)
            assertTrue(capture.truncated)
            assertEquals(64, capture.byteCount)
            assertEquals(bytes.take(64), target.readBytes().toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
