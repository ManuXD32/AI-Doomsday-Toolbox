package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcError
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Durable identity needed when a session is backed by a subagent catalog entry. */
internal data class NativeHarnessSessionAddress(
    val sessionId: String,
    val parentSessionId: String? = null,
    val mode: String? = null,
    val origin: String? = null,
) {
    val isSubagent: Boolean
        get() = parentSessionId != null || origin == SUBAGENT_ORIGIN
    val queueMutable: Boolean
        get() = !isSubagent || mode == CONTINUABLE_MODE
    val isRoutable: Boolean
        get() = !isSubagent || (parentSessionId != null && mode != null)

    fun toWire(): JsonObject? {
        if (!isRoutable) return null
        return if (!isSubagent) {
            buildJsonObject {
                put("kind", "session")
                put("sessionId", sessionId)
            }
        } else {
            buildJsonObject {
                put("kind", "subagent")
                put("parentSessionId", requireNotNull(parentSessionId))
                put("childSessionId", sessionId)
                put("mode", requireNotNull(mode))
            }
        }
    }

    companion object {
        const val CONTINUABLE_MODE = "continuable"
        const val ONE_SHOT_MODE = "one-shot"
        const val SUBAGENT_ORIGIN = "subagent"
    }
}

/**
 * A single address registry shared by list, follow, page, queue, and prompt actions.
 * A child with an unknown mode remains unroutable until the exact catalog row is read;
 * it is never silently routed as an ordinary session.
 */
internal class NativeHarnessSessionAddressStore {
    private val addresses = LinkedHashMap<String, NativeHarnessSessionAddress>()

    @Synchronized
    fun reset() = addresses.clear()

    @Synchronized
    fun observeListRow(row: JsonObject): NativeHarnessSessionAddress? {
        val sessionId = row.string("sessionId")?.takeIf { it.isNotBlank() } ?: return null
        val parent = (row.string("parentSessionId") ?: row.string("parentId"))
            ?.takeIf { it.isNotBlank() }
        val origin = row.string("origin")?.takeIf { it.isNotBlank() }
        val projectedMode = (row.objectValue("projections")
            ?.objectValue("values")
            ?.objectValue("subagent")
            ?.string("mode")
            ?: row.objectValue("projectionValues")?.objectValue("subagent")?.string("mode")
            ?: row.objectValue("subagent")?.string("mode"))
            ?.takeIf { it == NativeHarnessSessionAddress.CONTINUABLE_MODE || it == NativeHarnessSessionAddress.ONE_SHOT_MODE }
        val previous = addresses[sessionId]
        val preservedMode = previous?.takeIf { parent != null && it.parentSessionId == parent }?.mode
        val mode = projectedMode ?: preservedMode
        val address = NativeHarnessSessionAddress(sessionId, parent, mode, origin)
        addresses[sessionId] = address
        return address
    }

    @Synchronized
    fun observeSubagent(parentSessionId: String, childSessionId: String, mode: String?): NativeHarnessSessionAddress? {
        if (parentSessionId.isBlank() || childSessionId.isBlank()) return null
        val normalizedMode = mode?.takeIf { it == NativeHarnessSessionAddress.CONTINUABLE_MODE || it == NativeHarnessSessionAddress.ONE_SHOT_MODE }
        val previous = addresses[childSessionId]
        val preservedMode = previous?.takeIf { it.parentSessionId == parentSessionId }?.mode
        val address = NativeHarnessSessionAddress(
            sessionId = childSessionId,
            parentSessionId = parentSessionId,
            mode = normalizedMode ?: preservedMode,
            origin = NativeHarnessSessionAddress.SUBAGENT_ORIGIN,
        )
        addresses[childSessionId] = address
        return address
    }

    @Synchronized
    fun addressFor(sessionId: String): NativeHarnessSessionAddress? = addresses[sessionId]

    /** Unknown ordinary rows are safe to address by their session id; known children are not. */
    @Synchronized
    fun wireAddress(sessionId: String): JsonObject? {
        val known = addresses[sessionId]
        return known?.toWire() ?: if (known == null) ordinaryWireAddress(sessionId) else null
    }

    private fun ordinaryWireAddress(sessionId: String): JsonObject = buildJsonObject {
        put("kind", "session")
        put("sessionId", sessionId)
    }
}

/** Resolve a child mode from the authoritative parent catalog when list projections lack it. */
internal suspend fun NativeHarnessSessionAddressStore.ensureRoutable(
    client: HarnessClient,
    sessionId: String,
): NativeHarnessSessionAddress? {
    val current = addressFor(sessionId)
    if (current == null || !current.isSubagent || current.isRoutable) return current
    val parent = current.parentSessionId ?: return current
    when (val result = client.call(
        "subagents",
        "list",
        buildJsonObject { put("parentSessionId", parent) },
        HarnessCallPolicy.SafeRead,
    )) {
        is HarnessRpcResult.Failure -> return current
        is HarnessRpcResult.Success -> {
            result.value.objectArray("entries").forEach { entry ->
                if (entry.string("kind") == "child") {
                    observeSubagent(parent, entry.string("id").orEmpty(), entry.string("mode"))
                }
            }
        }
    }
    return addressFor(sessionId)
}

internal fun ordinaryHarnessSessionAddress(sessionId: String): NativeHarnessSessionAddress =
    NativeHarnessSessionAddress(sessionId = sessionId)

internal suspend fun promptNativeHarnessSession(
    client: HarnessClient,
    address: NativeHarnessSessionAddress,
    content: JsonArray,
    delivery: String,
    clientTimeZone: String,
): HarnessRpcResult {
    if (address.isSubagent) {
        if (!address.isRoutable) return addressUnavailableResult()
        if (address.mode != NativeHarnessSessionAddress.CONTINUABLE_MODE ||
            content.any { it.jsonObjectOrNull()?.string("type") == "file" }
        ) {
            return readOnlySubagentResult()
        }
        return client.call(
            "subagents",
            "prompt",
            buildJsonObject {
                put("requestId", UUID.randomUUID().toString())
                put("parentSessionId", requireNotNull(address.parentSessionId))
                put("childSessionId", address.sessionId)
                put("mode", NativeHarnessSessionAddress.CONTINUABLE_MODE)
                put("delivery", delivery)
                put("content", content)
                put("clientTimeZone", clientTimeZone)
            },
            HarnessCallPolicy.NoRetry,
        )
    }
    return client.promptSession(
        buildJsonObject {
            put("requestId", UUID.randomUUID().toString())
            put("sessionId", address.sessionId)
            put("mode", delivery)
            put("content", content)
            put("clientTimeZone", clientTimeZone)
        },
    )
}

internal suspend fun cancelNativeHarnessSession(
    client: HarnessClient,
    address: NativeHarnessSessionAddress,
): HarnessRpcResult = if (address.isSubagent) {
    if (!address.isRoutable) {
        addressUnavailableResult()
    } else {
        // The Host interrupt route always uses its continuable delivery marker;
        // it reads the durable child descriptor to reject non-resumable continuation.
        client.call(
            "subagents",
            "interruptByParent",
            buildJsonObject {
                put("childSessionId", address.sessionId)
                put("parentSessionId", requireNotNull(address.parentSessionId))
                put("mode", NativeHarnessSessionAddress.CONTINUABLE_MODE)
            },
            HarnessCallPolicy.NoRetry,
        )
    }
} else {
    client.cancelSession(buildJsonObject { put("sessionId", address.sessionId) })
}

private fun addressUnavailableResult(): HarnessRpcResult = HarnessRpcResult.Failure(
    HarnessRpcError(
        code = "SUBAGENT_ADDRESS_UNAVAILABLE",
        message = "The subagent address is not available yet",
    ),
)

private fun readOnlySubagentResult(): HarnessRpcResult = HarnessRpcResult.Failure(
    HarnessRpcError(
        code = "SUBAGENTS_READ_ONLY",
        message = "This subagent is read-only",
    ),
)
