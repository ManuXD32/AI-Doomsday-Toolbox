package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.llamadroid.data.HttpEndpointUrlSupport

/**
 * A reusable named endpoint for a remote agent engine.  Agent models stay on
 * [AgentRuntimeProfile], so one endpoint can be shared while every agent keeps
 * its own model selection.
 */
@Entity(
    tableName = "agent_runtime_endpoint_configs",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["backend"]),
        Index(value = ["updatedAt"])
    ]
)
data class AgentRuntimeEndpointConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val backend: String = AgentRuntimeBackend.OLLAMA.id,
    val baseUrl: String,
    val defaultModel: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toConfig(): AgentRuntimeEndpointConfig = AgentRuntimeEndpointConfig(
        id = id,
        name = name,
        backend = normalizeAgentRuntimeBackend(backend),
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        createdAt = createdAt,
        updatedAt = updatedAt
    ).normalized()

    companion object {
        fun fromConfig(config: AgentRuntimeEndpointConfig): AgentRuntimeEndpointConfigEntity {
            val normalized = config.normalized()
            return AgentRuntimeEndpointConfigEntity(
                id = normalized.id,
                name = normalized.name,
                backend = normalized.backend,
                baseUrl = normalized.baseUrl,
                defaultModel = normalized.defaultModel,
                createdAt = normalized.createdAt,
                updatedAt = normalized.updatedAt
            )
        }
    }
}

data class AgentRuntimeEndpointConfig(
    val id: Long = 0,
    val name: String,
    val backend: String = AgentRuntimeBackend.OLLAMA.id,
    val baseUrl: String,
    val defaultModel: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val normalizedBackend: AgentRuntimeBackend
        get() = AgentRuntimeBackend.from(backend)

    fun normalized(): AgentRuntimeEndpointConfig = copy(
        name = name.trim(),
        backend = normalizedBackend.id,
        baseUrl = HttpEndpointUrlSupport.normalizeBaseUrl(baseUrl)
            ?: baseUrl.trim().trimEnd('/'),
        defaultModel = defaultModel?.trim()?.takeIf { it.isNotEmpty() }
    )

    fun validate(): AgentRuntimeEndpointConfig {
        val normalized = normalized()
        require(normalized.name.isNotBlank()) { "Endpoint configuration name must not be blank" }
        require(normalized.normalizedBackend in AgentRuntimeBackend.remoteEndpointBackends) {
            "Endpoint configurations support Ollama, llama-server, and llama-swap only"
        }
        require(normalized.baseUrl.isNotBlank()) { "Endpoint URL must not be blank" }
        val normalizedBaseUrl = HttpEndpointUrlSupport.normalizeBaseUrl(normalized.baseUrl)
        require(
            normalizedBaseUrl != null
        ) {
            "Endpoint URL must start with http:// or https://"
        }
        return normalized.copy(baseUrl = normalizedBaseUrl)
    }
}

val AgentRuntimeBackend.Companion.remoteEndpointBackends: List<AgentRuntimeBackend>
    get() = listOf(
        AgentRuntimeBackend.OLLAMA,
        AgentRuntimeBackend.LLAMA_SERVER,
        AgentRuntimeBackend.LLAMA_SWAP
    )

fun List<AgentRuntimeProfile>.clearEndpointConfigReference(
    endpointConfigId: Long
): List<AgentRuntimeProfile> = map { profile ->
    if (profile.endpointConfigId == endpointConfigId) {
        // A deleted endpoint must not silently fall back to a different global
        // host. Keep an independently selected managed server, if any, and
        // clear the endpoint-provided model so dispatch shows Needs direction.
        profile.copy(endpointConfigId = null, model = null, updatedAt = System.currentTimeMillis())
    } else {
        profile
    }
}
