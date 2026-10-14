package com.example.llamadroid.service.compaction

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeChatContextWindowTest {
    @Test fun chatValueWinsAndIsClampedByManagedContext() {
        assertEquals(8192, NativeChatContextWindow.choose(8192, null, 32768))
        assertEquals(4096, NativeChatContextWindow.choose(8192, 4096, null))
    }

    @Test fun zeroChatContextFallsBackToManagedThenProbed() {
        assertEquals(16384, NativeChatContextWindow.choose(0, 16384, 32768))
        assertEquals(32768, NativeChatContextWindow.choose(0, null, 32768))
        assertEquals(32768, NativeChatContextWindow.choose(0, 0, 32768))
    }

    @Test fun nothingKnownIsZero() {
        assertEquals(0, NativeChatContextWindow.choose(0, null, null))
        assertEquals(0, NativeChatContextWindow.choose(-1, null, 0))
    }
}
