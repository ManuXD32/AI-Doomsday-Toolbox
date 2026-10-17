package com.example.llamadroid.service.chat

import com.example.llamadroid.service.KvSlotLease
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.compaction.NativeChatCompactionHooks
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Small helpers of the native-chat tool loop that keep `LlamaClientService` from growing.
 */

/**
 * Stop: once the HTTP call is cancelled and before the lease is released, asks the lease to wait for
 * an idle slot and save the chat's state so Continue and the next turn reuse the work. Never throws;
 * runs even though the turn's coroutine is already cancelled.
 */
internal suspend fun saveSlotAfterStop(lease: KvSlotLease) {
    withContext(NonCancellable) {
        try {
            val saved = lease.saveAfterStop()
            DebugLog.log("LlamaClientService: slot state saved after stop=$saved")
        } catch (error: Exception) {
            DebugLog.log("LlamaClientService: slot save after stop failed ${error.javaClass.simpleName}")
        }
    }
}

/**
 * Overflow compaction when the turn's own messages are not part of [base] (they are rebuilt from the
 * recorded steps each round): compacts [base] plus [turn] together, then keeps only the compacted base.
 * The kept tail always starts at a user message, so the turn messages stay at the end verbatim.
 */
internal suspend fun NativeChatCompactionHooks.Turn.onOverflowWithTurn(
    base: MutableList<OllamaService.ChatMessage>,
    turn: List<OllamaService.ChatMessage>,
    error: Throwable
): Boolean {
    if (turn.isEmpty()) return onOverflow(base, error)
    val combined = (base + turn).toMutableList()
    if (!onOverflow(combined, error)) return false
    base.clear()
    base.addAll(combined.dropLast(turn.size.coerceAtMost(combined.size)))
    return true
}
