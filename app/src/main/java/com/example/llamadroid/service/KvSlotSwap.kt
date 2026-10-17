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
    val nPromptTokens: Int,
    /** False when `/slots` could not be read after the request: the holder is trusted until a read proves otherwise. */
    val factsKnown: Boolean = true
) {
    /** True while the slot still reports the task recorded when this holder was stored. */
    fun matches(slot: KvSlotFact?): Boolean =
        if (!factsKnown) true
        else slot != null && idTask != null && idTask == slot.idTask &&
            nPromptTokens > 0 && nPromptTokens == slot.nPromptTokens
}

/**
 * A holder counts as disturbed only when `/slots` was read successfully ([slotsKnown]) and the
 * slot no longer matches it. A failed or timed-out read is UNKNOWN, never "disturbed".
 */
internal fun isHolderDisturbed(hasHolder: Boolean, slotsKnown: Boolean, holderMatches: Boolean): Boolean =
    hasHolder && slotsKnown && !holderMatches

/**
 * A restart is inferred from task ids dropping only when the `/slots` read succeeded; an
 * unreadable `/slots` (empty list) must not clear every holder.
 */
internal fun shouldInferRestart(slotsKnown: Boolean, previousMaxId: Int?, maxId: Int): Boolean =
    slotsKnown && previousMaxId != null && maxId < previousMaxId

/** Slot picked for a utility request and whether its current holder must be saved first. */
internal data class KvUtilitySlotChoice(val slot: Int, val saveHolder: Boolean)

/**
 * Picks the slot for a background utility request. [held] maps slots with a recorded holder to
 * "that holder's chat has a live turn"; [reserved] are slots assigned to a chat that has not run
 * yet. Preference: a never-held slot, then a slot whose holder is idle (saved first), then the
 * lowest slot (saved first). Null when the server reports no slots.
 */
internal fun chooseUtilitySlot(totalSlots: Int, held: Map<Int, Boolean>, reserved: Set<Int>): KvUtilitySlotChoice? {
    if (totalSlots <= 0) return null
    val all = 0 until totalSlots
    all.firstOrNull { it !in held && it !in reserved }?.let { return KvUtilitySlotChoice(it, saveHolder = false) }
    all.firstOrNull { held[it] != true && it !in reserved }?.let { return KvUtilitySlotChoice(it, saveHolder = true) }
    all.firstOrNull { held[it] != true }?.let { return KvUtilitySlotChoice(it, saveHolder = true) }
    return KvUtilitySlotChoice(all.first, saveHolder = true)
}
