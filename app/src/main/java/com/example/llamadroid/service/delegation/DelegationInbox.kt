package com.example.llamadroid.service.delegation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue

/** An answer from the subagent to the main LLM. [kind] is `reply`, `question` or `result`. */
data class DelegationSubReply(val kind: String, val text: String)

/**
 * In-memory mailbox of one running task: main-to-subagent messages not yet injected into the
 * subagent conversation, and a stream of the subagent's answers for `messageSubagent` to await.
 */
class DelegationInbox {
    private val queue = ConcurrentLinkedQueue<String>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    val replies = MutableSharedFlow<DelegationSubReply>(extraBufferCapacity = 16)

    fun post(text: String) {
        queue.add(text)
        signal.trySend(Unit)
    }

    fun hasPending(): Boolean = queue.isNotEmpty()

    fun drain(): List<String> {
        val out = ArrayList<String>()
        while (true) out += queue.poll() ?: break
        return out
    }

    /** Suspends until a message is pending; false on timeout. */
    suspend fun await(timeoutMs: Long): Boolean {
        if (hasPending()) return true
        return withTimeoutOrNull(timeoutMs) {
            while (!hasPending()) signal.receive()
            true
        } ?: false
    }

    fun emitReply(kind: String, text: String) {
        replies.tryEmit(DelegationSubReply(kind, text))
    }
}
