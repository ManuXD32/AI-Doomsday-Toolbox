package com.example.llamadroid.service

import com.example.llamadroid.data.model.LlamaServerEntity

/** What must happen on a llama-server slot before its next owner runs a request. */
data class KvSlotSwapDecision(val saveHolder: Boolean, val restore: Boolean) {
    companion object {
        val NONE = KvSlotSwapDecision(saveHolder = false, restore = false)
    }
}

/**
 * Pure swap protocol for a slot shared by several owners.
 *
 * - The current [holder] is saved first when it is someone else, has unsaved state and persists.
 *   A [slotDisturbed] slot (restart, or advanced by an unsynchronized request) no longer holds the
 *   holder's state, so there is nothing to save.
 * - [me] restores its file when it is valid and the slot does not already hold it.
 */
fun decideSwap(
    holder: KvCacheKey?,
    holderDirty: Boolean,
    holderPersists: Boolean,
    me: KvCacheKey,
    myFileValid: Boolean,
    slotDisturbed: Boolean = false
): KvSlotSwapDecision {
    val holderIsMe = holder == me && !slotDisturbed
    val saveHolder = holder != null && holder != me && holderDirty && holderPersists && !slotDisturbed
    val restore = myFileValid && !holderIsMe
    return KvSlotSwapDecision(saveHolder = saveHolder, restore = restore)
}

/** In-memory record of whose KV state a slot currently holds. Never persisted. */
internal data class KvSlotHolderState(
    val key: KvCacheKey,
    val persists: Boolean,
    val dirty: Boolean,
    val server: LlamaServerEntity?,
    val idTask: Int?,
    val nPromptTokens: Int
) {
    /** True while the slot still reports the task recorded when this holder was stored. */
    fun matches(slot: KvSlotFact?): Boolean =
        slot != null && idTask != null && idTask == slot.idTask &&
            nPromptTokens > 0 && nPromptTokens == slot.nPromptTokens
}
