package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Locale

/**
 * The persisted runtime choice for one built-in or custom Agent.
 *
 * This table deliberately contains only routing metadata. Prompts, tool
 * permissions, and chat content stay in their existing stores. The parent
 * database owns the schema version/migration registration for this entity.
 */
@Entity(
    tableName = "agent_runtime_profiles",
    indices = [Index("backend"), Index("updatedAt")]
)
data class AgentRuntimeProfileEntity(
    @PrimaryKey
    val agentKey: String,
    val backend: String = AgentRuntimeBackend.OLLAMA.id,
    val model: String? = null,
    val endpointConfigId: Long? = null,
    val managedLlamaServerId: Long? = null,
    val liteRtModelId: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toProfile(): AgentRuntimeProfile = AgentRuntimeProfile(
        agentKey = agentKey,
        backend = normalizeAgentRuntimeBackend(backend),
        model = model?.trim()?.takeIf { it.isNotEmpty() },
        endpointConfigId = endpointConfigId,
        managedLlamaServerId = managedLlamaServerId,
        liteRtModelId = liteRtModelId,
        updatedAt = updatedAt
    )

    companion object {
        fun fromProfile(profile: AgentRuntimeProfile): AgentRuntimeProfileEntity =
            AgentRuntimeProfileEntity(
                agentKey = profile.agentKey,
                backend = normalizeAgentRuntimeBackend(profile.backend),
                model = profile.model?.trim()?.takeIf { it.isNotEmpty() },
                endpointConfigId = profile.endpointConfigId,
                managedLlamaServerId = profile.managedLlamaServerId,
                liteRtModelId = profile.liteRtModelId,
                updatedAt = profile.updatedAt
            )
    }
}

/** Stable backend identifiers used by profile persistence and dispatch. */
enum class AgentRuntimeBackend(val id: String) {
    OLLAMA("ollama"),
    LLAMA_SERVER("llama-server"),
    LLAMA_SWAP("llama-swap"),
    LITERT("litert-lm");

    companion object {
        fun from(value: String?): AgentRuntimeBackend = when (
            value?.trim()?.lowercase(Locale.US)?.replace('_', '-')
        ) {
            "llama-server", "llama.cpp", "llamacpp", "llama" -> LLAMA_SERVER
            "llama-swap", "llamaswap" -> LLAMA_SWAP
            "litert", "litert-lm", "litertlm", "lite-rt", "lite-rt-lm" -> LITERT
            else -> OLLAMA
        }
    }
}

fun normalizeAgentRuntimeBackend(value: String?): String =
    AgentRuntimeBackend.from(value).id

/**
 * Built-in role keys are intentionally plain names so they match existing
 * AgentRole values and old SharedPreferences keys.
 */
object AgentRuntimeProfileKeys {
    const val ORCHESTRATOR = "ORCHESTRATOR"
    const val CODEBASE_SCOUT = "CODEBASE_SCOUT"
    const val RESEARCHER = "RESEARCHER"
    const val PLANNER = "PLANNER"
    const val CODER = "CODER"
    const val REVIEWER = "REVIEWER"
    const val EXECUTOR = "EXECUTOR"
    const val SUMMARIZER = "SUMMARIZER"
    const val VISUAL_TESTER = "VISUAL_TESTER"

    val builtIn: List<String> = listOf(
        ORCHESTRATOR,
        CODEBASE_SCOUT,
        RESEARCHER,
        PLANNER,
        CODER,
        REVIEWER,
        EXECUTOR,
        SUMMARIZER,
        VISUAL_TESTER
    )

    fun custom(name: String): String {
        val normalized = name.trim().uppercase(Locale.US)
        require(normalized.isNotEmpty()) { "Custom agent name must not be blank" }
        return "CUSTOM:$normalized"
    }

    fun isCustom(key: String): Boolean = key.startsWith("CUSTOM:")
}

/** Immutable snapshot captured before a dispatch starts. */
data class AgentRuntimeProfile(
    val agentKey: String,
    val backend: String,
    val model: String? = null,
    val endpointConfigId: Long? = null,
    val managedLlamaServerId: Long? = null,
    val liteRtModelId: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    init {
        require(agentKey.isNotBlank()) { "agentKey must not be blank" }
    }

    val normalizedBackend: AgentRuntimeBackend
        get() = AgentRuntimeBackend.from(backend)

    fun normalized(): AgentRuntimeProfile = copy(
        backend = normalizedBackend.id,
        model = model?.trim()?.takeIf { it.isNotEmpty() }
    )
}
