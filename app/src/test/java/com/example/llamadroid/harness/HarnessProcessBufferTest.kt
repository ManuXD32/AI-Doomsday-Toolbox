package com.example.llamadroid.harness

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessProcessBufferTest {
    @Test fun slowReaderGetsLossFlagAndMonotonicCursor() {
        val buffer = HarnessProcessBuffer(8)
        buffer.append(byteArrayOf(0, 1, 2, 3, 4, 5), 6)
        val first = buffer.read(0)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5), first.bytes)
        assertEquals(6L, first.offset)
        assertFalse(first.truncated)
        buffer.append(byteArrayOf(6, 7, 8, 9, 10, 11), 6)
        val slow = buffer.read(0)
        assertTrue(slow.truncated)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7, 8, 9, 10, 11), slow.bytes)
        assertEquals(12L, slow.offset)
        assertArrayEquals(byteArrayOf(6, 7, 8, 9, 10, 11), buffer.read(first.offset).bytes)
        assertThrows(IllegalArgumentException::class.java) { buffer.read(13) }
    }
}
