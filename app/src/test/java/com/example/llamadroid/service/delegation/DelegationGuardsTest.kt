package com.example.llamadroid.service.delegation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationGuardsTest {
    @Test
    fun modelCallAndTimeLimits() {
        val g = DelegationGuards(modelCalls = DelegationGuards.MAX_MODEL_CALLS)
        assertEquals("step_limit", (g.beforeModelCall() as GuardDecision.Fail).code)
        val old = DelegationGuards(startedAtMs = 0L)
        assertEquals("time_limit", (old.beforeModelCall(DelegationGuards.MAX_WALL_CLOCK_MS + 1) as GuardDecision.Fail).code)
        assertTrue(DelegationGuards(startedAtMs = 0L).beforeModelCall(1_000L) is GuardDecision.Continue)
    }

    @Test
    fun fiveConsecutiveToolErrorsFail() {
        val g = DelegationGuards()
        repeat(4) { assertTrue(g.onToolResult("a$it", it, true) is GuardDecision.Continue) }
        assertEquals("tool_errors", (g.onToolResult("a9", 9, true) as GuardDecision.Fail).code)
    }

    @Test
    fun successResetsErrorStreak() {
        val g = DelegationGuards()
        repeat(4) { g.onToolResult("e$it", it, true) }
        g.onToolResult("ok", 1, false)
        assertTrue(g.onToolResult("e", 100, true) is GuardDecision.Continue)
    }

    @Test
    fun repeatedIdenticalCallWarnsThenNeedsDirection() {
        val g = DelegationGuards()
        assertTrue(g.onToolResult("wait", 7, false) is GuardDecision.Continue)
        assertTrue(g.onToolResult("wait", 7, false) is GuardDecision.Continue)
        assertTrue(g.onToolResult("wait", 7, false) is GuardDecision.Warn)
        assertTrue(g.onToolResult("wait", 7, false) is GuardDecision.Continue)
        assertTrue(g.onToolResult("wait", 7, false) is GuardDecision.NeedsDirection)
        // A different result breaks the streak.
        val h = DelegationGuards()
        repeat(4) { h.onToolResult("wait", it, false) }
        assertTrue(h.onToolResult("wait", 99, false) is GuardDecision.Continue)
    }

    @Test
    fun unchangedWaitsNeedDirectionAfterSix() {
        val g = DelegationGuards()
        assertTrue(g.onWaitResult("h", false) is GuardDecision.Continue)
        repeat(5) { assertTrue(g.onWaitResult("h", false) is GuardDecision.Continue) }
        assertTrue(g.onWaitResult("h", false) is GuardDecision.NeedsDirection)
    }

    @Test
    fun userOwnedApprovalsDoNotCountAsUnchanged() {
        val g = DelegationGuards()
        repeat(20) { assertTrue(g.onWaitResult("same", true) is GuardDecision.Continue) }
        assertTrue(g.onWaitResult("same", false) is GuardDecision.Continue)
    }

    @Test
    fun textOnlyRepliesNudgeThenNeedDirection() {
        val g = DelegationGuards()
        assertTrue(g.onTextOnlyReply() is GuardDecision.Nudge)
        assertTrue(g.onTextOnlyReply() is GuardDecision.Nudge)
        assertTrue(g.onTextOnlyReply() is GuardDecision.NeedsDirection)
        val h = DelegationGuards()
        h.onTextOnlyReply(); h.onToolCallsReceived()
        assertTrue(h.onTextOnlyReply() is GuardDecision.Nudge)
    }

    @Test
    fun harnessPromptCap() {
        val g = DelegationGuards(harnessPrompts = DelegationGuards.MAX_HARNESS_PROMPTS - 1)
        assertTrue(g.canSendPrompt())
        g.onPromptSent()
        assertFalse(g.canSendPrompt())
    }

    @Test
    fun providerFailuresCountDistinctTurnsAndResetOnSuccess() {
        val g = DelegationGuards()
        assertEquals(1, g.onProviderTurn("CONTEXT_WINDOW_EXCEEDED", 3, false))
        assertEquals(1, g.onProviderTurn("CONTEXT_WINDOW_EXCEEDED", 3, false))
        assertEquals(2, g.onProviderTurn("CONTEXT_WINDOW_EXCEEDED", 4, false))
        assertEquals(0, g.onProviderTurn(null, 5, true))
        assertEquals(1, g.onProviderTurn("PROVIDER_INTERRUPTED", 6, false))
        g.resetStreaks()
        assertEquals(1, g.onProviderTurn("PROVIDER_INTERRUPTED", 6, false))
    }
}
