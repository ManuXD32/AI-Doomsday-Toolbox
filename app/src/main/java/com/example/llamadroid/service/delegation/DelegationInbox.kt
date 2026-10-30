package com.example.llamadroid.service.delegation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** An answer from the subagent to the main LLM. [kind] is `reply`, `question` or `result`. */
data class DelegationSubReply(val kind: String, val text: String)

/**
 * Low-latency signal of one running task. The durable mailbox (Room rows with `delivered`) is the
 * truth: [signal] only says "undelivered main-to-subagent rows may exist", so the loop and the
 * harness wait re-read them. [emitReply] tells the mailbox that the subagent posted a row.
 */
class DelegationInbox(private val onSubReply: (kind: String, text: String) -> Unit = { _, _ -> }) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var pending = false

    /** Called after a MAIN_TO_SUB row was inserted. */
    fun signal() {
        pending = true
        wake.trySend(Unit)
    }

    fun hasPending(): Boolean = pending

    /** The loop calls this BEFORE reading the rows, so a signal during the read is not lost. */
    fun clearPending() {
        pending = false
    }

    /** Suspends until a signal is pending; false on timeout. */
    suspend fun await(timeoutMs: Long): Boolean {
        if (pending) return true
        return withTimeoutOrNull(timeoutMs) {
            while (!pending) wake.receive()
            true
        } ?: false
    }

    /** The subagent posted a reply/question/result row (already inserted, undelivered). */
    fun emitReply(kind: String, text: String) {
        onSubReply(kind, text)
    }
}
