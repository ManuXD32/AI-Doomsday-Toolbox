package com.example.llamadroid.ui.agent

import com.example.llamadroid.data.db.AgentRuntimeEndpointConfig
import com.example.llamadroid.data.db.AgentRuntimeBackend
import com.example.llamadroid.data.db.AgentRuntimeProfile
import com.example.llamadroid.data.db.AgentRuntimeProfileKeys
import com.example.llamadroid.data.runtime.ManagedLlamaServerDescriptor

/**
 * UI-only resolution of the runtime assigned to the agent currently shown in the console.
 *
 * Dispatch has its own authoritative resolver. This small snapshot exists so the console
 * does not accidentally combine a custom agent with the built-in role's profile or display
 * health from a different global endpoint.
 */
internal data class AgentRuntimeUiResolution(
    val profile: AgentRuntimeProfile?,
    val endpointConfig: AgentRuntimeEndpointConfig?,
    val managedServer: ManagedLlamaServerDescriptor?,
    val endpointReferenceMissing: Boolean
) {
    val hasNamedEndpoint: Boolean
        get() = endpointConfig != null

    val hasManagedServer: Boolean
        get() = endpointConfig == null && managedServer != null

    val hasManagedServerAssignment: Boolean
        get() = endpointConfig == null &&
            !endpointReferenceMissing &&
            profile?.managedLlamaServerId != null

    /**
     * True when the profile is using the process-wide llama-server connection.
     *
     * A null managed-server id is the explicit Global choice. Keep a missing
     * profile in this bucket as well so the first composition can still use the
     * legacy global backend while profile migration is settling.
     */
    val usesGlobalLlamaServer: Boolean
        get() = endpointConfig == null &&
            !endpointReferenceMissing &&
            !hasManagedServerAssignment &&
            (profile == null || profile.normalizedBackend == AgentRuntimeBackend.LLAMA_SERVER)

    val backendId: String?
        get() = endpointConfig?.normalizedBackend?.id ?: profile?.normalizedBackend?.id

    /** Shows the resolved server model, then the profile override or endpoint default. */
    val model: String?
        get() = managedServer?.modelName?.takeIf { it.isNotBlank() }
            ?: profile?.model?.takeIf { it.isNotBlank() }
            ?: endpointConfig?.defaultModel?.takeIf { it.isNotBlank() }

    val targetLabel: String?
        get() = endpointConfig?.name?.takeIf { it.isNotBlank() }
            ?: managedServer?.displayName?.takeIf { it.isNotBlank() }
}

/** Returns the stable profile key used by both built-in and custom agent settings. */
internal fun agentRuntimeProfileKeyForUi(
    currentAgentName: String,
    customAgentName: String?
): String = customAgentName
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let(AgentRuntimeProfileKeys::custom)
    ?: currentAgentName

/**
 * Resolves the endpoint first, then the managed server only when no named endpoint is
 * assigned. A missing endpoint reference intentionally suppresses the server fallback so
 * the UI can expose the broken assignment instead of showing unrelated global state.
 */
internal fun resolveAgentRuntimeUi(
    profile: AgentRuntimeProfile?,
    endpointConfigs: List<AgentRuntimeEndpointConfig>,
    managedServers: List<ManagedLlamaServerDescriptor>
): AgentRuntimeUiResolution {
    val normalizedProfile = profile?.normalized()
    val endpointId = normalizedProfile?.endpointConfigId
    val endpoint = endpointId?.let { id -> endpointConfigs.firstOrNull { it.id == id } }
        ?.normalized()
        ?.takeIf { it.baseUrl.isNotBlank() }
    val endpointReferenceMissing = endpointId != null && endpoint == null
    val managedServer = if (endpointReferenceMissing || endpoint != null) {
        null
    } else {
        normalizedProfile?.managedLlamaServerId?.let { id ->
            managedServers.firstOrNull { it.id == id }
        }
    }
    return AgentRuntimeUiResolution(
        profile = normalizedProfile,
        endpointConfig = endpoint,
        managedServer = managedServer,
        endpointReferenceMissing = endpointReferenceMissing
    )
}
