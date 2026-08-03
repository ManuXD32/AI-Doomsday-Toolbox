package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentForegroundServiceTest {
    @Test
    fun `resolveAgentResumeDispatch uses plain resume when service already runs`() {
        val dispatch = resolveAgentResumeDispatch(isServiceRunning = true)

        assertEquals(AgentForegroundService.ACTION_RESUME_RUNTIME, dispatch.action)
        assertFalse(dispatch.useForegroundStart)
        assertFalse(dispatch.recoveryOnly)
        assertEquals("resume_running", dispatch.startSource)
    }

    @Test
    fun `resolveAgentResumeDispatch uses foreground recovery start when service is cold`() {
        val dispatch = resolveAgentResumeDispatch(isServiceRunning = false)

        assertEquals(AgentForegroundService.ACTION_START_AGENT, dispatch.action)
        assertTrue(dispatch.useForegroundStart)
        assertTrue(dispatch.recoveryOnly)
        assertEquals("resume_cold", dispatch.startSource)
    }

    @Test
    fun `cold resume does not start foreground service when no runtime job is recoverable`() {
        assertFalse(
            shouldStartAgentRecoveryForeground(
                isServiceRunning = false,
                hasRecoverableJobs = false
            )
        )
    }

    @Test
    fun `cold resume starts foreground service when runtime work is recoverable`() {
        assertTrue(
            shouldStartAgentRecoveryForeground(
                isServiceRunning = false,
                hasRecoverableJobs = true
            )
        )
    }

    @Test
    fun `crashed Agent work restores interrupted and requires explicit Continue`() {
        val decision = interruptedAgentRecoveryDecision()

        assertEquals(AgentService.RESUME_STATE_INTERRUPTED, decision.resumeState)
        assertEquals(AiRuntimeJobStore.STATUS_CANCELLED, decision.runtimeJobStatus)
        assertTrue(decision.requiresExplicitContinue)
    }
}
