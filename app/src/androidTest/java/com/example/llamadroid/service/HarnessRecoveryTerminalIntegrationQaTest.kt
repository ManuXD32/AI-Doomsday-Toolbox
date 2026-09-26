package com.example.llamadroid.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in API36 x86 coverage for the recovery broker, PRoot, Bash and Termux PTY chain. */
@RunWith(AndroidJUnit4::class)
class HarnessRecoveryTerminalIntegrationQaTest {
    @Test
    fun qaStartWriteReadAndStopInteractiveRecoveryShell() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the explicit x86_64 Harness QA carrier", BuildConfig.HARNESS_QA_X86)
        assumeTrue(
            "Pass recovery_terminal_qa=true for disposable API36 PTY coverage",
            args.getString("recovery_terminal_qa") == "true"
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = HarnessRecoveryTerminalSessionManager.get(context)
        val marker = "ADT_RECOVERY_PTY_QA_${System.nanoTime()}"
        try {
            val opened = manager.open().getOrThrow()
            assertEquals(HarnessRecoveryTerminalStatus.CONNECTED, opened.status)
            assertEquals(HarnessRecoveryTerminalPhase.CONNECTED, opened.phase)
            assertTrue((opened.processId ?: 0) > 0)
            val terminal = requireNotNull(manager.terminalSession())
            assertNotNull(terminal.emulator)

            manager.send("printf '%s\\n' $marker").getOrThrow()
            withTimeout(10_000L) {
                while (terminal.emulator?.screen?.transcriptText?.contains(marker) != true) {
                    delay(50L)
                }
            }
            assertTrue(terminal.isRunning)
        } finally {
            manager.close().getOrThrow()
            assertTrue(manager.state.value.status == HarnessRecoveryTerminalStatus.CLOSED)
        }
    }
}
