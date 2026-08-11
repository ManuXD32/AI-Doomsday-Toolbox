package com.example.llamadroid.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProcessCleanupRecordedOwnerTest {
    @Test
    fun `recorded cleanup requires matching pid start token llama command and port`() {
        val command = "/data/user/0/app/lib/libllama-server.so --host 127.0.0.1 --port 8080"
        assertTrue(
            NativeProcessCleanup.recordedLlamaOwnerMatches(
                expectedPid = 321,
                expectedStartTimeTicks = 777L,
                expectedPort = 8080,
                actualPid = 321,
                actualStartTimeTicks = 777L,
                actualCommandLine = command
            )
        )
        assertFalse(
            NativeProcessCleanup.recordedLlamaOwnerMatches(
                expectedPid = 321,
                expectedStartTimeTicks = 777L,
                expectedPort = 8080,
                actualPid = 321,
                actualStartTimeTicks = 778L,
                actualCommandLine = command
            )
        )
        assertFalse(
            NativeProcessCleanup.recordedLlamaOwnerMatches(
                expectedPid = 321,
                expectedStartTimeTicks = 777L,
                expectedPort = 8081,
                actualPid = 321,
                actualStartTimeTicks = 777L,
                actualCommandLine = command
            )
        )
        assertFalse(
            NativeProcessCleanup.recordedLlamaOwnerMatches(
                expectedPid = 321,
                expectedStartTimeTicks = 777L,
                expectedPort = 8080,
                actualPid = 321,
                actualStartTimeTicks = 777L,
                actualCommandLine = "/data/user/0/app/lib/unrelated-server --port 8080"
            )
        )
    }
}
