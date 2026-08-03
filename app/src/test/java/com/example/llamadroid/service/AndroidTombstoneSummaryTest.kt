package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTombstoneSummaryTest {
    @Test
    fun `extracts bounded signal cause faulting thread and native frames`() {
        val signal = message(
            varint(1, 11), string(2, "SIGSEGV"), varint(3, 2), string(4, "SEGV_ACCERR"),
            varint(9, 0x79a1L)
        )
        val frame = message(string(4, "android::IPCThreadState::talkWithDriver"),
            string(6, "/system/lib64/libbinder.so"), string(8, "build-id"))
        val threadDetails = message(varint(1, 42), string(2, "binder:42_2"), bytes(4, frame))
        val threadEnvelope = message(varint(1, 42), bytes(2, threadDetails))
        val tombstone = message(bytes(10, signal), string(15, "stack pointer is not in a rw map"),
            varint(6, 42), bytes(16, threadEnvelope))

        val result = requireNotNull(summarizeAndroidTombstone(tombstone))

        assertEquals("SIGSEGV", result.signal)
        assertEquals("SEGV_ACCERR", result.signalCode)
        assertEquals(0x79a1L, result.faultAddress)
        assertEquals("stack pointer is not in a rw map", result.cause)
        assertEquals("binder:42_2", result.faultingThread)
        assertEquals("android::IPCThreadState::talkWithDriver", result.nativeFrames.single().function)
        assertEquals("build-id", result.nativeFrames.single().buildId)
    }

    @Test
    fun `returns null for malformed tombstone without throwing`() {
        assertNull(summarizeAndroidTombstone(byteArrayOf(0x52, 0x80.toByte())))
    }

    @Test
    fun `falls back cleanly when a trace has no tombstone fields`() {
        assertNull(summarizeAndroidTombstone("fatal signal 11\nbacktrace\n".toByteArray()))
    }

    @Test
    fun `handles a valid trace larger than 470 KiB and skips oversized unknown payloads`() {
        val signal = message(string(2, "SIGABRT"))
        val trace = message(bytes(10, signal), bytes(99, ByteArray(480 * 1024)))

        assertEquals("SIGABRT", summarizeAndroidTombstone(trace)?.signal)
        assertNull(summarizeAndroidTombstone(trace, maxBytes = 32))
        assertTrue(requireNotNull(summarizeAndroidTombstone(trace)).compactText().length < 2_048)
    }

    @Test
    fun `rejects a trace above the eight MiB safety cap`() {
        val oversized = ByteArray(8 * 1024 * 1024 + 1)
        assertNull(summarizeAndroidTombstone(oversized))
    }

    @Test
    fun `retains later relevant frames while bounding the frame list`() {
        val signal = message(string(2, "SIGSEGV"))
        val frames = (0 until 20).map { index ->
            bytes(4, message(string(4, "frame-$index"), string(6, "lib-$index.so")))
        }
        val details = message(varint(1, 7), string(2, "DefaultDispatch"), *frames.toTypedArray())
        val trace = message(varint(6, 7), bytes(10, signal), bytes(16, message(varint(1, 7), bytes(2, details))))

        val result = requireNotNull(summarizeAndroidTombstone(trace))

        assertEquals(16, result.nativeFrames.size)
        assertEquals("frame-15", result.nativeFrames.last().function)
    }

    private fun message(vararg fields: ByteArray): ByteArray = fields.fold(ByteArray(0)) { all, field -> all + field }
    private fun varint(field: Int, value: Long): ByteArray = encode((field shl 3).toLong()) + encode(value)
    private fun string(field: Int, value: String): ByteArray = bytes(field, value.toByteArray())
    private fun bytes(field: Int, value: ByteArray): ByteArray = encode(((field shl 3) or 2).toLong()) + encode(value.size.toLong()) + value
    private fun encode(value: Long): ByteArray {
        var remaining = value
        val result = ArrayList<Byte>()
        do {
            var current = (remaining and 0x7f).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) current = current or 0x80
            result += current.toByte()
        } while (remaining != 0L)
        return result.toByteArray()
    }
}
