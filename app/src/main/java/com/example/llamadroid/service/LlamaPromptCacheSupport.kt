package com.example.llamadroid.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class LlamaSlotOwnerKey(
    val endpointGeneration: String,
    val modelConfiguration: String,
    val conversationId: String,
    val agentSessionId: String,
    /**
     * Normalized base URL of the llama-server this owner talks to (see
     * [normalizePhysicalEndpoint]). When set, every owner of the same physical server shares one
     * slot pool regardless of its generation string; null keeps the per-generation pool.
     */
    val physicalEndpoint: String? = null
) {
    val endpointKey: String
        get() = endpointGeneration.substringBefore('|')

    /** Key of the slot pool this owner allocates from. */
    val allocationKey: String
        get() = physicalEndpoint ?: endpointGeneration
}

data class LlamaSlotAssignment(
    val owner: LlamaSlotOwnerKey,
    val slotId: Int,
    val lastUsedAt: Long,
    val promptFingerprint: String?
)

/**
 * A slot held for one owner for the length of a turn. No lock is held while a reservation is
 * open; [LlamaSlotManager.withSlotRequest] takes the per-slot lock for one request.
 */
class LlamaSlotReservation internal constructor(
    val owner: LlamaSlotOwnerKey,
    val slotId: Int
) {
    val allocationKey: String get() = owner.allocationKey
}

/** Lowercases scheme/host, unifies loopback spellings and drops trailing slashes and paths. */
fun normalizePhysicalEndpoint(baseUrl: String): String {
    val trimmed = baseUrl.trim()
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    val rawHost = uri?.host
    if (uri == null || scheme == null || rawHost == null) return trimmed.trimEnd('/').lowercase()
    val host = rawHost.lowercase().trim('[', ']').let {
        if (it == "localhost" || it == "0.0.0.0" || it == "::1") "127.0.0.1" else it
    }
    val hostText = if (host.contains(':')) "[$host]" else host
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    return "$scheme://$hostText$port"
}

/**
 * In-memory ownership is deliberate: a numeric slot is unsafe to restore after
 * an app/server process restart unless the exact server generation is known.
 * Conversation history remains durable; a cold restart simply accepts a cache
 * miss and reconstructs the canonical request.
 */
object LlamaSlotManager {
    private val stateLock = Any()
    private val assignments = mutableMapOf<LlamaSlotOwnerKey, LlamaSlotAssignment>()
    private val activeOwners = mutableSetOf<LlamaSlotOwnerKey>()
    private val reservedOwners = mutableMapOf<LlamaSlotOwnerKey, Int>()
    private val unsupportedEndpointGenerations = mutableSetOf<String>()
    private val slotMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withAssignedSlot(
        owner: LlamaSlotOwnerKey?,
        slotCount: Int?,
        affinityMode: LlamaSlotAffinityMode,
        promptFingerprint: String? = null,
        block: suspend (Int?) -> T
    ): T {
        val reservation = reserve(owner, slotCount, affinityMode, promptFingerprint)
            ?: return block(null)
        try {
            return withSlotRequest(reservation, promptFingerprint) { block(reservation.slotId) }
        } finally {
            release(reservation)
        }
    }

    /**
     * Turn-level affinity without a lock. Returns null when affinity is disabled, unsupported or
     * no slot is available. With a [LlamaSlotOwnerKey.physicalEndpoint] and every slot reserved,
     * the least recently used slot is shared instead of returning null.
     */
    fun reserve(
        owner: LlamaSlotOwnerKey?,
        slotCount: Int?,
        affinityMode: LlamaSlotAffinityMode,
        promptFingerprint: String? = null
    ): LlamaSlotReservation? {
        if (owner == null || affinityMode == LlamaSlotAffinityMode.DISABLED) return null
        val usableSlotCount = slotCount?.takeIf { it > 0 }
            ?: if (affinityMode == LlamaSlotAffinityMode.ENABLED) 1 else null
            ?: return null
        if (isUnsupported(owner.endpointGeneration)) return null
        return synchronized(stateLock) {
            val assignment = assignments[owner]?.takeIf { it.slotId in 0 until usableSlotCount }
                ?: allocateLocked(owner, usableSlotCount, promptFingerprint)
                ?: return@synchronized null
            reservedOwners[owner] = (reservedOwners[owner] ?: 0) + 1
            LlamaSlotReservation(owner, assignment.slotId)
        }
    }

    /** Ends a [reserve]; the assignment itself stays for later turns. */
    fun release(reservation: LlamaSlotReservation) {
        synchronized(stateLock) {
            val left = (reservedOwners[reservation.owner] ?: 0) - 1
            if (left > 0) reservedOwners[reservation.owner] = left else reservedOwners.remove(reservation.owner)
        }
    }

    /** Runs one request under the per-slot lock of [reservation]. */
    suspend fun <T> withSlotRequest(
        reservation: LlamaSlotReservation,
        promptFingerprint: String? = null,
        block: suspend () -> T
    ): T {
        val owner = reservation.owner
        return withSlotRequest(reservation.allocationKey, reservation.slotId) {
            synchronized(stateLock) {
                activeOwners += owner
                val current = assignments[owner]
                if (current != null) {
                    assignments[owner] = current.copy(
                        lastUsedAt = System.currentTimeMillis(),
                        promptFingerprint = promptFingerprint ?: current.promptFingerprint
                    )
                }
            }
            try {
                block()
            } finally {
                synchronized(stateLock) {
                    activeOwners -= owner
                    assignments[owner]?.let {
                        assignments[owner] = it.copy(lastUsedAt = System.currentTimeMillis())
                    }
                }
            }
        }
    }

    /** Per-slot lock only (no ownership bookkeeping); used for saves outside a request. */
    suspend fun <T> withSlotRequest(allocationKey: String, slotId: Int, block: suspend () -> T): T {
        val mutex = slotMutexes.getOrPut("$allocationKey|$slotId") { Mutex() }
        return mutex.withLock { block() }
    }

    fun markSlotSelectionUnsupported(endpointGeneration: String) {
        synchronized(stateLock) {
            unsupportedEndpointGenerations += endpointGeneration
            invalidateEndpointGenerationLocked(endpointGeneration)
        }
    }

    fun isUnsupported(endpointGeneration: String): Boolean =
        synchronized(stateLock) { endpointGeneration in unsupportedEndpointGenerations }

    /** Drops assignments grouped by generation string or by physical endpoint. */
    fun invalidateEndpointGeneration(endpointGeneration: String) {
        synchronized(stateLock) {
            invalidateEndpointGenerationLocked(endpointGeneration)
        }
        slotMutexes.keys
            .filter { it.startsWith("$endpointGeneration|") }
            .forEach(slotMutexes::remove)
    }

    fun invalidateAll() {
        synchronized(stateLock) {
            assignments.clear()
            activeOwners.clear()
            reservedOwners.clear()
            unsupportedEndpointGenerations.clear()
        }
        slotMutexes.clear()
    }

    internal fun snapshotAssignments(): List<LlamaSlotAssignment> =
        synchronized(stateLock) { assignments.values.sortedBy { it.slotId } }

    private fun isBusyLocked(owner: LlamaSlotOwnerKey): Boolean =
        owner in activeOwners || owner in reservedOwners

    private fun allocateLocked(
        owner: LlamaSlotOwnerKey,
        slotCount: Int,
        promptFingerprint: String?
    ): LlamaSlotAssignment? {
        val allocationKey = owner.allocationKey
        val physical = owner.physicalEndpoint != null
        val pool = assignments.values.filter { it.owner.allocationKey == allocationKey }
        // Affinity: the same conversation under a new owner key (for example a new attempt id)
        // takes over the slot its previous key used, so its prompt cache stays warm.
        if (physical) {
            pool.filter {
                it.owner != owner && it.owner.conversationId == owner.conversationId &&
                    it.slotId in 0 until slotCount && !isBusyLocked(it.owner)
            }.maxByOrNull { it.lastUsedAt }?.let { previous ->
                assignments.remove(previous.owner)
                return assign(owner, previous.slotId, promptFingerprint)
            }
        }
        val occupied = pool.mapTo(mutableSetOf()) { it.slotId }
        val freeSlot = (0 until slotCount).firstOrNull { it !in occupied }
        val evictedSlot = if (freeSlot != null) null else pool
            .filterNot { isBusyLocked(it.owner) }
            .minByOrNull { it.lastUsedAt }
            ?.also { assignments.remove(it.owner) }
            ?.slotId
        // Every slot is busy: a physical pool shares its least recently used slot.
        val sharedSlot = if (freeSlot == null && evictedSlot == null && physical) {
            pool.filter { it.slotId in 0 until slotCount }.minByOrNull { it.lastUsedAt }?.slotId
        } else null
        val slotId = freeSlot ?: evictedSlot ?: sharedSlot ?: return null
        return assign(owner, slotId, promptFingerprint)
    }

    private fun assign(owner: LlamaSlotOwnerKey, slotId: Int, promptFingerprint: String?) =
        LlamaSlotAssignment(
            owner = owner,
            slotId = slotId,
            lastUsedAt = System.currentTimeMillis(),
            promptFingerprint = promptFingerprint
        ).also { assignments[owner] = it }

    private fun invalidateEndpointGenerationLocked(endpointGeneration: String) {
        assignments.keys.removeAll {
            it.endpointGeneration == endpointGeneration || it.physicalEndpoint == endpointGeneration
        }
        activeOwners.removeAll {
            it.endpointGeneration == endpointGeneration || it.physicalEndpoint == endpointGeneration
        }
        reservedOwners.keys.removeAll {
            it.endpointGeneration == endpointGeneration || it.physicalEndpoint == endpointGeneration
        }
    }
}

internal fun canonicalToolArguments(toolCall: OllamaService.ToolCall): String =
    ToolArgumentsJson.canonical(toolCall.rawArgumentsJson, toolCall.arguments.toSortedMap())

internal fun stableToolCallId(name: String, rawArgumentsJson: String): String {
    val hash = sha256Hex("$name\u0000$rawArgumentsJson")
    return "call_${hash.take(24)}"
}

internal fun buildLlamaPromptCacheDiagnostics(
    messages: List<OllamaService.ChatMessage>,
    tools: List<AgentTool>,
    thinkingEnabled: Boolean
): LlamaPromptCacheDiagnostics {
    val stableSystem = messages.firstOrNull { it.role == "system" }?.content.orEmpty()
    val canonicalTools = canonicalToolsJson(tools)
    val stablePrefix = buildString {
        append(stableSystem)
        append("\u001fthinking=")
        append(thinkingEnabled)
        append("\u001ftools=")
        append(canonicalTools)
    }
    return LlamaPromptCacheDiagnostics(
        systemPromptHash = sha256Hex(stableSystem),
        toolDefinitionsHash = sha256Hex(canonicalTools),
        stablePrefixHash = sha256Hex(stablePrefix),
        messageCount = messages.size,
        toolCount = tools.size
    )
}

internal fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun isRecognizedSlotSelectionError(error: Throwable): Boolean {
    val message = error.message.orEmpty().lowercase()
    return "id_slot" in message ||
        "slot id" in message ||
        "slot out of range" in message ||
        ("slot" in message && ("unsupported" in message || "not support" in message))
}
