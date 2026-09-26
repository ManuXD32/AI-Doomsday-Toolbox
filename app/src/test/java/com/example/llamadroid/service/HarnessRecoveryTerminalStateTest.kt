package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HarnessRecoveryTerminalStateTest {
    @Test
    fun onlyConnectedStateEnablesInput() {
        assertFalse(HarnessRecoveryTerminalState().isConnected)
        assertTrue(
            HarnessRecoveryTerminalState(
                status = HarnessRecoveryTerminalStatus.CONNECTED,
                phase = HarnessRecoveryTerminalPhase.CONNECTED,
                sessionId = "recovery_test"
            ).isConnected
        )
        assertFalse(
            HarnessRecoveryTerminalState(
                status = HarnessRecoveryTerminalStatus.FAILED,
                errorCode = "RECOVERY_TERMINAL_START_FAILED"
            ).isConnected
        )
    }

    @Test
    fun startupPhaseAndExitStatusRemainMetadataOnly() {
        val state = HarnessRecoveryTerminalState(
            status = HarnessRecoveryTerminalStatus.FAILED,
            phase = HarnessRecoveryTerminalPhase.FAILED,
            exitStatus = 127,
            failurePhase = HarnessRecoveryTerminalPhase.CONNECTED,
            errorCode = "RECOVERY_TERMINAL_EXITED"
        )

        assertFalse(state.isConnected)
        assertTrue(state.phase == HarnessRecoveryTerminalPhase.FAILED)
        assertTrue(state.failurePhase == HarnessRecoveryTerminalPhase.CONNECTED)
        assertTrue(state.exitStatus == 127)
        assertTrue(state.errorCode?.startsWith("RECOVERY_TERMINAL_") == true)
    }

    @Test
    fun guestShellFailureUsesStablePhaseCode() {
        assertTrue(
            harnessRecoveryTerminalFailureCode(HarnessRecoveryTerminalPhase.WAITING_FOR_GUEST_SHELL) ==
                "RECOVERY_TERMINAL_GUEST_SHELL_NOT_READY"
        )
    }

    @Test
    fun recoveryLaunchPassesKillOnExitToProotAfterBrokerDelimiter() {
        val arguments = harnessRecoveryTerminalProcessArguments(
            broker = File("/native/libproot_broker.so"),
            proot = File("/native/libproot.so")
        )
        val delimiter = arguments.indexOf("--")

        assertTrue("Broker/PRoot delimiter is missing", delimiter > 0)
        assertEquals("/native/libproot.so", arguments[delimiter + 1])
        assertEquals("--kill-on-exit", arguments[delimiter + 2])
        assertFalse(arguments.subList(0, delimiter).contains("--kill-on-exit"))
    }
}
