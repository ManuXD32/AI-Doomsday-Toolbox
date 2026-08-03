package com.example.llamadroid.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class LlamaSlotOwnerKey(
    val endpointGeneration: String,
    val modelConfiguration: String,
    val conversationId: String,
    val agentSessionId: String
) {
    val endpointKey: String
        get() = endpointGeneration.substringBefore('|')
}

data class LlamaSlotAssignment(
    val owner: LlamaSlotOwnerKey,
    val slotId: Int,
    val lastUsedAt: Long,
    val promptFingerprint: String?
)

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
    private val unsupportedEndpointGenerations = mutableSetOf<String>()
    private val slotMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withAssignedSlot(
        owner: LlamaSlotOwnerKey?,
        slotCount: Int?,
        affinityMode: LlamaSlotAffinityMode,
        promptFingerprint: String? = null,
        block: suspend (Int?) -> T
    ): T {
        if (owner == null || affinityMode == LlamaSlotAffinityMode.DISABLED) {
            return block(null)
        }
        val usableSlotCount = slotCount?.takeIf { it > 0 }
            ?: if (affinityMode == LlamaSlotAffinityMode.ENABLED) 1 else null
            ?: return block(null)
        if (isUnsupported(owner.endpointGeneration)) return block(null)

        val assignment = synchronized(stateLock) {
            assignments[owner]?.takeIf { it.slotId in 0 until usableSlotCount }
                ?: allocateLocked(owner, usableSlotCount, promptFingerprint)
        } ?: return block(null)

        val mutexKey = "${owner.endpointGeneration}|${assignment.slotId}"
        val mutex = slotMutexes.getOrPut(mutexKey) { Mutex() }
        return mutex.withLock {
            synchronized(stateLock) {
                activeOwners += owner
                assignments[owner] = assignment.copy(
                    lastUsedAt = System.currentTimeMillis(),
                    promptFingerprint = promptFingerprint ?: assignment.promptFingerprint
                )
            }
            try {
                block(assignment.slotId)
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

    fun markSlotSelectionUnsupported(endpointGeneration: String) {
        synchronized(stateLock) {
            unsupportedEndpointGenerations += endpointGeneration
            invalidateEndpointGenerationLocked(endpointGeneration)
        }
    }

    fun isUnsupported(endpointGeneration: String): Boolean =
        synchronized(stateLock) { endpointGeneration in unsupportedEndpointGenerations }

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
            unsupportedEndpointGenerations.clear()
        }
        slotMutexes.clear()
    }

    internal fun snapshotAssignments(): List<LlamaSlotAssignment> =
        synchronized(stateLock) { assignments.values.sortedBy { it.slotId } }

    private fun allocateLocked(
        owner: LlamaSlotOwnerKey,
        slotCount: Int,
        promptFingerprint: String?
    ): LlamaSlotAssignment? {
        val sameGeneration = assignments.values.filter {
            it.owner.endpointGeneration == owner.endpointGeneration
        }
        val occupied = sameGeneration.mapTo(mutableSetOf()) { it.slotId }
        val freeSlot = (0 until slotCount).firstOrNull { it !in occupied }
        val slotId = freeSlot ?: sameGeneration
            .filterNot { it.owner in activeOwners }
            .minByOrNull { it.lastUsedAt }
            ?.also { assignments.remove(it.owner) }
            ?.slotId
            ?: return null
        return LlamaSlotAssignment(
            owner = owner,
            slotId = slotId,
            lastUsedAt = System.currentTimeMillis(),
            promptFingerprint = promptFingerprint
        ).also { assignments[owner] = it }
    }

    private fun invalidateEndpointGenerationLocked(endpointGeneration: String) {
        assignments.keys.removeAll { it.endpointGeneration == endpointGeneration }
        activeOwners.removeAll { it.endpointGeneration == endpointGeneration }
    }
}

internal fun canonicalToolArguments(toolCall: OllamaService.ToolCall): String {
    toolCall.rawArgumentsJson?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
        if (runCatching { JSONObject(raw) }.isSuccess) return raw
    }
    return JSONObject().apply {
        toolCall.arguments.toSortedMap().forEach { (key, value) -> put(key, value) }
    }.toString()
}

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
