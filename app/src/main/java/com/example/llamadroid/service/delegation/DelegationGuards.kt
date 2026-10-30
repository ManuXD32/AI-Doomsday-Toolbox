package com.example.llamadroid.service.delegation

/** What the subagent loop must do after a guard check. */
sealed class GuardDecision {
    data object Continue : GuardDecision()
    /** Inject [text] as a user row, keep going. */
    data class Warn(val text: String) : GuardDecision()
    /** Inject [text] as a user nudge row, keep going. */
    data class Nudge(val text: String) : GuardDecision()
    data class NeedsDirection(
        val reason: String,
        /** Stable viewer error code stored on the task (null = none). */
        val errorCode: String? = null
    ) : GuardDecision()
    /** Terminal failure with a stable viewer error [code] (`step_limit`, `time_limit`, `tool_errors`). */
    data class Fail(val code: String, val reason: String) : GuardDecision()
}

/**
 * Pure counters behind the runaway-loop guards (300 model calls, 40 harness prompts, 5 consecutive
 * tool errors, repeated identical calls, unchanged digests, text-only replies, 8 h wall clock).
 */
class DelegationGuards(
    var modelCalls: Int = 0,
    var harnessPrompts: Int = 0,
    private val startedAtMs: Long = System.currentTimeMillis()
) {
    private var consecutiveToolErrors = 0
    private var consecutiveTextOnly = 0
    private var unchangedWaits = 0
    private var lastDigestHash: String? = null
    private var lastSignature: String? = null
    private var repeatCount = 0
    private var consecutiveProviderFailures = 0
    private var lastFailureTurn = -1

    /** Resets the per-run streak counters (used when the user continues a stopped task). */
    fun resetStreaks() {
        consecutiveToolErrors = 0
        consecutiveTextOnly = 0
        unchangedWaits = 0
        lastDigestHash = null
        lastSignature = null
        repeatCount = 0
        consecutiveProviderFailures = 0
        lastFailureTurn = -1
    }

    fun beforeModelCall(nowMs: Long = System.currentTimeMillis()): GuardDecision = when {
        modelCalls >= MAX_MODEL_CALLS ->
            GuardDecision.Fail("step_limit", "The subagent reached the limit of $MAX_MODEL_CALLS model calls.")
        nowMs - startedAtMs > MAX_WALL_CLOCK_MS ->
            GuardDecision.Fail("time_limit", "The subagent ran for more than 8 hours.")
        else -> GuardDecision.Continue
    }

    fun onModelCallDone() { modelCalls++ }

    fun canSendPrompt(): Boolean = harnessPrompts < MAX_HARNESS_PROMPTS

    fun onPromptSent() { harnessPrompts++ }

    /** A reply with no tool call. */
    fun onTextOnlyReply(): GuardDecision {
        consecutiveTextOnly++
        return when {
            consecutiveTextOnly >= MAX_TEXT_ONLY ->
                GuardDecision.NeedsDirection("The subagent answered $MAX_TEXT_ONLY times in a row without using a tool.")
            else -> GuardDecision.Nudge(DelegateSubagentPrompt.NUDGE)
        }
    }

    fun onToolCallsReceived() { consecutiveTextOnly = 0 }

    /** [resultHash] identifies the result text (any stable hash). */
    fun onToolResult(signature: String, resultHash: Int, isError: Boolean): GuardDecision {
        if (isError) consecutiveToolErrors++ else consecutiveToolErrors = 0
        if (consecutiveToolErrors >= MAX_CONSECUTIVE_TOOL_ERRORS) {
            return GuardDecision.Fail("tool_errors", "$MAX_CONSECUTIVE_TOOL_ERRORS tool calls failed in a row.")
        }
        val full = "$signature#$resultHash"
        if (full == lastSignature) repeatCount++ else { lastSignature = full; repeatCount = 1 }
        return when {
            repeatCount >= REPEAT_NEEDS_DIRECTION ->
                GuardDecision.NeedsDirection("The subagent repeated the same tool call with the same result $repeatCount times.")
            repeatCount == REPEAT_WARN ->
                GuardDecision.Warn(DelegateSubagentPrompt.REPEAT_WARNING)
            else -> GuardDecision.Continue
        }
    }

    /**
     * Called after each harness_wait. [userOwnedPendingOnly] is true while only approvals left to
     * the user ("Ask me") are pending; such waits never count as unchanged.
     */
    fun onWaitResult(digestHash: String, userOwnedPendingOnly: Boolean = false): GuardDecision {
        if (userOwnedPendingOnly) { unchangedWaits = 0; lastDigestHash = digestHash; return GuardDecision.Continue }
        if (digestHash == lastDigestHash) unchangedWaits++ else { unchangedWaits = 0; lastDigestHash = digestHash }
        return if (unchangedWaits >= MAX_UNCHANGED_WAITS) {
            GuardDecision.NeedsDirection("The harness made no visible progress over $MAX_UNCHANGED_WAITS waits.")
        } else GuardDecision.Continue
    }

    /**
     * Called after each harness_wait. [failureCode] is the digest's provider failure code (null when
     * the turn did not fail for a provider reason); [turn] identifies the failed turn so waiting twice
     * on the same failure counts once. A turn that ended without a failure resets the counter.
     * Returns the number of consecutive provider failures after this wait.
     */
    fun onProviderTurn(failureCode: String?, turn: Int, turnEndedCleanly: Boolean): Int {
        if (failureCode != null) {
            if (turn != lastFailureTurn) { lastFailureTurn = turn; consecutiveProviderFailures++ }
        } else if (turnEndedCleanly) {
            consecutiveProviderFailures = 0
            lastFailureTurn = -1
        }
        return consecutiveProviderFailures
    }

    companion object {
        const val MAX_PROVIDER_FAILURES = 2
        const val MAX_MODEL_CALLS = 300
        const val MAX_HARNESS_PROMPTS = 40
        const val MAX_CONSECUTIVE_TOOL_ERRORS = 5
        const val REPEAT_WARN = 3
        const val REPEAT_NEEDS_DIRECTION = 5
        const val MAX_UNCHANGED_WAITS = 6
        const val MAX_TEXT_ONLY = 3
        const val MAX_WALL_CLOCK_MS = 8L * 60 * 60 * 1000
    }
}
