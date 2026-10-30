package com.example.llamadroid.service.chat

import com.example.llamadroid.service.ram.LlamaRamEstimate

/** What limits how many chats can generate at once on a resource. */
enum class AdmissionKind {
    /** A llama-server endpoint: up to [AdmissionTarget.capacity] (slot count) sessions at once. */
    ENDPOINT,

    /** The single in-process LiteRT engine: one session at a time. */
    ENGINE,

    /** Ollama and remote non-llama servers: the server queues internally. */
    UNLIMITED
}

/** Why a chat is waiting; the service maps it to `LlamaClientService.QueueReason`. */
enum class AdmissionWait { SLOT, RAM, ENGINE }

/**
 * @property resourceKey normalized base URL (or `card:<id>` for managed cards, `litert` for the
 *   engine); sessions with equal keys share the resource.
 * @property ramEstimate set when admission would start a local server that is not running yet.
 */
data class AdmissionTarget(
    val chatId: Long,
    val kind: AdmissionKind,
    val resourceKey: String,
    val label: String,
    val capacity: Int = 1,
    val ramEstimate: LlamaRamEstimate? = null
) {
    val ramNeedBytes: Long get() = ramEstimate?.totalBytes ?: 0L
}

sealed interface AdmissionDecision {
    object Start : AdmissionDecision
    data class Queued(val reason: AdmissionWait, val position: Int) : AdmissionDecision
}

data class QueuedChat(
    val chatId: Long,
    val reason: AdmissionWait,
    val position: Int,
    val label: String
)

/**
 * Pure admission rules for concurrent Native Chat generations; all state is in memory.
 *
 * - An endpoint admits sessions while the active count is below its slot capacity; otherwise the
 *   chat waits for [AdmissionWait.SLOT] in FIFO order per endpoint.
 * - The first session on a not-running local server must pass [ramFits] with the sum of
 *   [reservedRamBytes] (servers admitted but not yet up) added to the demand, but only while a
 *   chat is active on ANOTHER local model server: waiting can only help while something of ours
 *   that will finish is using memory. Otherwise it starts as before and Android manages memory.
 *   A blocked chat waits for [AdmissionWait.RAM] until a session ends or [recheck] finds memory.
 * - LiteRT runs one session at a time ([AdmissionWait.ENGINE]); unlimited kinds always start.
 * - A later waiter never overtakes an earlier waiter of the same resource.
 */
class ChatAdmissionController(
    private val ramFits: (target: AdmissionTarget, reservedBytes: Long) -> Boolean = { _, _ -> true }
) {
    private class Active(val target: AdmissionTarget, var ramReserved: Boolean)

    private val active = LinkedHashMap<Long, Active>()
    private val waiting = ArrayList<AdmissionTarget>()

    @Synchronized
    fun request(target: AdmissionTarget): AdmissionDecision {
        if (active.containsKey(target.chatId)) return AdmissionDecision.Start
        waiting.firstOrNull { it.chatId == target.chatId }?.let { return queuedDecision(it) }
        // FIFO: never overtake an earlier waiter of the same resource.
        val behindWaiter = waiting.any { it.resourceKey == target.resourceKey }
        if (!behindWaiter && blockReason(target) == null) {
            admit(target)
            return AdmissionDecision.Start
        }
        waiting += target
        return queuedDecision(target)
    }

    /** Ends [chatId] (active or waiting) and returns the waiters that may start now, FIFO. */
    @Synchronized
    fun finish(chatId: Long): List<AdmissionTarget> {
        active.remove(chatId)
        waiting.removeAll { it.chatId == chatId }
        return drain()
    }

    /** Removes a waiting chat. [removed] is false when it was not queued. */
    @Synchronized
    fun cancel(chatId: Long): CancelResult {
        val removed = waiting.removeAll { it.chatId == chatId }
        return CancelResult(removed, if (removed) drain() else emptyList())
    }

    data class CancelResult(val removed: Boolean, val started: List<AdmissionTarget>)

    /** The admitted server answers now: its memory is real, so it no longer counts as reserved. */
    @Synchronized
    fun markServerUp(chatId: Long): List<AdmissionTarget> {
        active[chatId]?.ramReserved = false
        return drain()
    }

    /** Re-evaluates every waiter (free memory may have changed). */
    @Synchronized
    fun recheck(): List<AdmissionTarget> = drain()

    @Synchronized
    fun isActive(chatId: Long): Boolean = active.containsKey(chatId)

    @Synchronized
    fun isQueued(chatId: Long): Boolean = waiting.any { it.chatId == chatId }

    @Synchronized
    fun activeCount(resourceKey: String): Int = active.values.count { it.target.resourceKey == resourceKey }

    @Synchronized
    fun hasRamWaiters(): Boolean = waiting.any { it.ramNeedBytes > 0L && activeOn(it.resourceKey) == 0 }

    /** Waiting chats in arrival order with their per-resource position (1-based). */
    @Synchronized
    fun queuedSnapshot(): List<QueuedChat> {
        val seen = HashMap<String, Int>()
        val lastReason = HashMap<String, AdmissionWait>()
        return waiting.map { target ->
            val position = (seen[target.resourceKey] ?: 0) + 1
            seen[target.resourceKey] = position
            val reason = blockReason(target) ?: lastReason[target.resourceKey] ?: AdmissionWait.SLOT
            lastReason[target.resourceKey] = reason
            QueuedChat(target.chatId, reason, position, target.label)
        }
    }

    private fun queuedDecision(target: AdmissionTarget): AdmissionDecision.Queued {
        val entry = queuedSnapshot().first { it.chatId == target.chatId }
        return AdmissionDecision.Queued(entry.reason, entry.position)
    }

    private fun activeOn(key: String): Int = active.values.count { it.target.resourceKey == key }

    /** Another local model server (managed card) has an active chat that will free its slot. */
    private fun otherLocalModelActive(target: AdmissionTarget): Boolean = active.values.any {
        it.target.kind == AdmissionKind.ENDPOINT && it.target.resourceKey != target.resourceKey &&
            it.target.resourceKey.startsWith(MANAGED_KEY_PREFIX)
    }

    private fun reservedBytes(): Long =
        active.values.filter { it.ramReserved }.sumOf { it.target.ramNeedBytes }

    private fun admit(target: AdmissionTarget) {
        active[target.chatId] = Active(target, ramReserved = target.ramNeedBytes > 0L && activeOn(target.resourceKey) == 0)
    }

    /** Null when [target] may start now, else the reason it has to wait. */
    private fun blockReason(target: AdmissionTarget): AdmissionWait? = when (target.kind) {
        AdmissionKind.UNLIMITED -> null
        AdmissionKind.ENGINE -> if (activeOn(target.resourceKey) >= 1) AdmissionWait.ENGINE else null
        AdmissionKind.ENDPOINT -> {
            val running = activeOn(target.resourceKey)
            when {
                running >= target.capacity.coerceAtLeast(1) -> AdmissionWait.SLOT
                running == 0 && target.ramNeedBytes > 0L && otherLocalModelActive(target) &&
                    !ramFits(target, reservedBytes()) -> AdmissionWait.RAM
                else -> null
            }
        }
    }

    companion object {
        /** Resource-key prefix of managed local servers (see `ChatAdmissionPlanner`). */
        const val MANAGED_KEY_PREFIX = "card:"
    }

    private fun drain(): List<AdmissionTarget> {
        val started = ArrayList<AdmissionTarget>()
        val blocked = HashSet<String>()
        val iterator = waiting.iterator()
        while (iterator.hasNext()) {
            val target = iterator.next()
            if (target.resourceKey in blocked) continue
            if (blockReason(target) == null) {
                iterator.remove()
                admit(target)
                started += target
            } else {
                blocked += target.resourceKey
            }
        }
        return started
    }
}
