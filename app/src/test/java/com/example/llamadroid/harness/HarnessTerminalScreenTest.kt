package com.example.llamadroid.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections

class HarnessTerminalScreenTest {
    @Test
    fun sharedDefaultKeyContractIncludesSameModifiersAndNavigationKeys() {
        assertEquals(
            listOf("ctrl", "alt", "shift", "tab", "escape", "up", "down", "left", "right"),
            HARNESS_TERMINAL_DEFAULT_KEYS,
        )
    }

    @Test fun modifiersApplyToOrdinaryInputWithoutInventingALineEnding() {
        assertEquals("\u0003", composeHarnessTerminalText("c", ctrl = true, alt = false, shift = false))
        assertEquals("\u001bb", composeHarnessTerminalText("b", ctrl = false, alt = true, shift = false))
        assertEquals("A", composeHarnessTerminalText("a", ctrl = false, alt = false, shift = true))
    }
    @Test
    fun `plain terminal keys use portable sequences`() {
        assertEquals("\t", composeHarnessTerminalKeySequence(HarnessTerminalKey.TAB))
        assertEquals("\u001b", composeHarnessTerminalKeySequence(HarnessTerminalKey.ESCAPE))
        assertEquals("\u001b[A", composeHarnessTerminalKeySequence(HarnessTerminalKey.UP))
        assertEquals("\u001b[D", composeHarnessTerminalKeySequence(HarnessTerminalKey.LEFT))
    }

    @Test
    fun `modifier combinations retain terminal modifier bits`() {
        assertEquals("\u001b[Z", composeHarnessTerminalKeySequence(HarnessTerminalKey.TAB, shift = true))
        assertEquals("\u001b[27;5;9~", composeHarnessTerminalKeySequence(HarnessTerminalKey.TAB, ctrl = true))
        assertEquals("\u001b[1;3C", composeHarnessTerminalKeySequence(HarnessTerminalKey.RIGHT, alt = true))
        assertEquals("\u001b[1;4A", composeHarnessTerminalKeySequence(HarnessTerminalKey.UP, shift = true, alt = true))
        assertEquals("\u001b[1;6A", composeHarnessTerminalKeySequence(HarnessTerminalKey.UP, shift = true, ctrl = true))
    }

    @Test
    fun nativeTransportEncodesImeControlAndAltInput() {
        assertEquals("\u0003", composeHarnessTermuxCodePoint('c'.code, ctrl = true, alt = false))
        assertEquals("\u001bb", composeHarnessTermuxCodePoint('b'.code, ctrl = false, alt = true))
        assertEquals("x", composeHarnessTermuxCodePoint('x'.code, ctrl = false, alt = false))
    }

    @Test
    fun nativeTransportSerializesInputWrites() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = Collections.synchronizedList(mutableListOf<String>())
        val adapter = HarnessTermuxTransportAdapter(
            onWrite = { data ->
                if (data == "first") firstStarted.complete(Unit)
                release.await()
                writes += data
            },
        )
        try {
            adapter.send("first")
            withTimeout(2_000) { firstStarted.await() }
            adapter.send("second")
            release.complete(Unit)
            withTimeout(2_000) {
                while (writes.size < 2) delay(1)
            }
            assertEquals(listOf("first", "second"), writes.toList())
        } finally {
            adapter.close()
        }
    }

    @Test
    fun nativeTransportReportsInputQueueOverflow(): Unit = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val adapter = HarnessTermuxTransportAdapter(
            onWrite = {
                firstStarted.complete(Unit)
                release.await()
            },
            onFailure = { failures += it },
        )
        try {
            adapter.send("first")
            withTimeout(2_000) { firstStarted.await() }
            repeat(64) { adapter.send("queued-$it") }
            adapter.send("overflow")
            assertTrue(failures.any { it.message == "TERMINAL_INPUT_QUEUE_EXHAUSTED" })
            release.complete(Unit)
        } finally {
            adapter.close()
        }
    }
}
