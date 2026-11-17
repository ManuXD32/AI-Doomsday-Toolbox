package com.example.llamadroid.service.chat

/** Decides what a new generation request for a chat does when an older session may still exist. */
internal object GenerationStartGate {
    /** How long a start waits for a stopped session's job to finish before giving up. */
    const val WAIT_TIMEOUT_MS = 10_000L

    enum class Decision { START, WAIT_THEN_START, REJECT_BUSY }

    fun decide(hasSession: Boolean, stopRequested: Boolean): Decision = when {
        !hasSession -> Decision.START
        stopRequested -> Decision.WAIT_THEN_START
        else -> Decision.REJECT_BUSY
    }
}
