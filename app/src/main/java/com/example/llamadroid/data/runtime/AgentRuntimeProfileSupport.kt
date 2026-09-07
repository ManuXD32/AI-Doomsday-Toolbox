package com.example.llamadroid.data.runtime

import com.example.llamadroid.data.HttpEndpointUrlSupport
import com.example.llamadroid.data.db.AgentRuntimeBackend
import com.example.llamadroid.data.db.AgentRuntimeEndpointConfig
import com.example.llamadroid.data.db.AgentRuntimeEndpointConfigDao
import com.example.llamadroid.data.db.AgentRuntimeEndpointConfigEntity
import com.example.llamadroid.data.db.AgentRuntimeProfile
import com.example.llamadroid.data.db.AgentRuntimeProfileDao
import com.example.llamadroid.data.db.AgentRuntimeProfileEntity
import com.example.llamadroid.data.db.AgentRuntimeProfileKeys
import com.example.llamadroid.data.db.normalizeAgentRuntimeBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.net.URI
import java.util.Locale

/** Runtime status exposed by the managed-server owner, without coupling to its implementation. */
enum class ManagedLlamaServerState {
    RUNNING,
    STARTING,
    LOADING,
    STOPPED,
    ERROR,
    MISSING,
    UNKNOWN
}

/**
 * Small boundary object that sibling managed-server implementations can map to.
 * No process start/stop method is exposed on purpose: Agent dispatch may only
 * observe the assigned server and ask the user to continue when it is not ready.
 */
data class ManagedLlamaServerDescriptor(
    val id: Long,
    val displayName: String,
    val host: String,
    val port: Int,
    val backend: String = AgentRuntimeBackend.LLAMA_SERVER.id,
    val modelName: String? = null,
    val state: ManagedLlamaServerState = ManagedLlamaServerState.UNKNOWN
) {
    val normalizedBackend: String get() = normalizeAgentRuntimeBackend(backend)
    val isReady: Boolean get() = state == ManagedLlamaServerState.RUNNING
    val baseUrl: String? get() = HttpEndpointUrlSupport.fromHostPort(host, port)

    /** Safe compact label for narrow UI surfaces. */
    fun compactLabel(): String = buildString {
        append(displayName.trim().ifBlank { "llama-server" })
        if (port in 1..65535) append(" · ").append(port)
        modelName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append(" · ").append(it.substringAfterLast('/'))
        }
    }
}

/** Read-only catalog implemented by the managed llama server owner. */
interface ManagedLlamaServerCatalog {
    fun observeServers(): Flow<List<ManagedLlamaServerDescriptor>>
    suspend fun getServer(id: Long): ManagedLlamaServerDescriptor?
}

object EmptyManagedLlamaServerCatalog : ManagedLlamaServerCatalog {
    override fun observeServers(): Flow<List<ManagedLlamaServerDescriptor>> = flowOf(emptyList())
    override suspend fun getServer(id: Long): ManagedLlamaServerDescriptor? = null
}

/** Optional LiteRT catalog boundary; the profile layer does not own model files. */
interface AgentLiteRtModelCatalog {
    suspend fun containsModel(id: Long): Boolean
}

object EmptyAgentLiteRtModelCatalog : AgentLiteRtModelCatalog {
    override suspend fun containsModel(id: Long): Boolean = false
}

/** Legacy global/per-role values read from SharedPreferences by the caller. */
data class LegacyAgentRuntimeSettings(
    val globalBackend: String? = null,
    val globalModel: String? = null,
    val llamaServerUrl: String? = null,
    val llamaServerModelLabel: String? = null,
    val liteRtModelId: Long? = null,
    val roleModels: Map<String, String?> = emptyMap(),
    val customModels: Map<String, String?> = emptyMap()
)

/**
 * Optional settings owned by the General agent card.
 *
 * This is deliberately separate from every role profile.  The card can be
 * edited without rewriting the individual built-in/custom preferences, and
 * the [enabled] bit is the only switch that makes these values authoritative
 * for a dispatch.
 */
data class AgentRuntimeGlobalOverride(
    val enabled: Boolean = false,
    val backend: String = AgentRuntimeBackend.OLLAMA.id,
    val model: String? = null,
    val endpointConfigId: Long? = null,
    val managedLlamaServerId: Long? = null,
    val liteRtModelId: Long? = null,
    val liteRtBackend: String = "auto",
    val liteRtMtpEnabled: Boolean = false,
    val contextSize: Int = 16_384,
    val maxOutputTokens: Int = 8_096,
    val thinkingEnabled: Boolean = true,
    val visionEnabled: Boolean = true
) {
    val normalizedBackend: AgentRuntimeBackend
        get() = AgentRuntimeBackend.from(backend)

    fun normalized(): AgentRuntimeGlobalOverride {
        val normalizedEndpoint = endpointConfigId?.takeIf { it > 0L }
        return copy(
            backend = normalizedBackend.id,
            model = model?.trim()?.takeIf { it.isNotEmpty() },
            endpointConfigId = normalizedEndpoint,
            // A named endpoint is authoritative over a managed-server card.
            managedLlamaServerId = managedLlamaServerId?.takeIf { normalizedEndpoint == null },
            liteRtModelId = liteRtModelId?.takeIf { it > 0L },
            liteRtBackend = liteRtBackend.trim().ifBlank { "auto" },
            contextSize = contextSize.coerceIn(1_024, 1_048_576),
            maxOutputTokens = maxOutputTokens.coerceIn(1, 1_048_576)
        )
    }

    /** Apply only when enabled; disabled overrides are intentionally inert. */
    fun applyTo(profile: AgentRuntimeProfile): AgentRuntimeProfile =
        if (!enabled) {
            profile
        } else {
            val effective = normalized()
            profile.copy(
                backend = effective.backend,
                model = effective.model,
                endpointConfigId = effective.endpointConfigId,
                managedLlamaServerId = effective.managedLlamaServerId,
                liteRtModelId = effective.liteRtModelId
            ).normalized()
        }
}

/**
 * Immutable settings captured at the start of an agent dispatch.
 *
 * Keeping routing and tuning in one value prevents callers from accidentally
 * combining a global backend with a role-local model/context or vice versa.
 */
data class AgentRuntimeDispatchSettings(
    val backend: String,
    val model: String?,
    val endpointConfigId: Long? = null,
    val managedLlamaServerId: Long? = null,
    val liteRtModelId: Long? = null,
    val liteRtBackend: String = "auto",
    val liteRtMtpEnabled: Boolean = false,
    val contextSize: Int,
    val maxOutputTokens: Int,
    val thinkingEnabled: Boolean,
    val visionEnabled: Boolean
) {
    val normalizedBackend: AgentRuntimeBackend
        get() = AgentRuntimeBackend.from(backend)

    fun normalized(): AgentRuntimeDispatchSettings = copy(
        backend = normalizedBackend.id,
        model = model?.trim()?.takeIf { it.isNotEmpty() },
        endpointConfigId = endpointConfigId?.takeIf { it > 0L },
        managedLlamaServerId = managedLlamaServerId?.takeIf {
            endpointConfigId == null
        },
        liteRtModelId = liteRtModelId?.takeIf { it > 0L },
        liteRtBackend = liteRtBackend.trim().ifBlank { "auto" },
        contextSize = contextSize.coerceIn(1_024, 1_048_576),
        maxOutputTokens = maxOutputTokens.coerceIn(1, 1_048_576)
    )
}

data class AgentRuntimeProfileMigrationResult(
    val profiles: List<AgentRuntimeProfile>,
    val createdAgentKeys: Set<String>,
    val requiresServerSelection: Set<String>,
    val boundManagedServerIds: Map<String, Long>,
    val alreadyHadProfiles: Set<String>
)

/** Pure, deterministic migration from the old global/per-role settings. */
object AgentRuntimeProfileMigration {
    const val VERSION = 1

    fun migrate(
        builtInAgentKeys: Collection<String> = AgentRuntimeProfileKeys.builtIn,
        customAgentNames: Collection<String> = emptyList(),
        legacy: LegacyAgentRuntimeSettings,
        managedServers: List<ManagedLlamaServerDescriptor>,
        existingProfiles: Collection<AgentRuntimeProfile> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): AgentRuntimeProfileMigrationResult {
        val existingByKey = existingProfiles.associateBy { it.agentKey }
        val requestedKeys = buildList {
            addAll(builtInAgentKeys.map(String::trim).filter(String::isNotEmpty))
            addAll(customAgentNames.map(AgentRuntimeProfileKeys::custom))
        }.distinct()
        val backend = AgentRuntimeBackend.from(legacy.globalBackend)
        val serverBinding = if (backend == AgentRuntimeBackend.LLAMA_SERVER) {
            resolveLegacyServerBinding(legacy.llamaServerUrl, managedServers, backend)
        } else {
            LegacyServerBinding.Unneeded
        }
        val profiles = requestedKeys.map { key ->
            existingByKey[key] ?: run {
                val customName = key.removePrefix("CUSTOM:")
                val model = if (AgentRuntimeProfileKeys.isCustom(key)) {
                    lookupLegacyModel(legacy.customModels, customName)
                        ?: lookupLegacyModel(legacy.customModels, key)
                        ?: legacy.globalModel
                } else {
                    lookupLegacyModel(legacy.roleModels, key) ?: legacy.globalModel
                }
                AgentRuntimeProfile(
                    agentKey = key,
                    backend = backend.id,
                    model = model?.trim()?.takeIf { it.isNotEmpty() }
                        ?: legacy.llamaServerModelLabel?.trim()?.takeIf {
                            backend == AgentRuntimeBackend.LLAMA_SERVER && it.isNotEmpty()
                        },
                    managedLlamaServerId = (serverBinding as? LegacyServerBinding.Bound)?.id,
                    liteRtModelId = legacy.liteRtModelId?.takeIf {
                        backend == AgentRuntimeBackend.LITERT && it > 0L
                    },
                    updatedAt = now
                )
            }
        }
        // A legacy llama-server profile which did not bind unambiguously to a
        // managed card continues to use the legacy global URL. Null is the
        // canonical global target; explicit managed-without-card uses id 0.
        val requiresSelection = emptySet<String>()
        val created = profiles.filter { it.agentKey !in existingByKey }.map { it.agentKey }.toSet()
        val bound = profiles.mapNotNull { profile ->
            profile.managedLlamaServerId?.let { profile.agentKey to it }
        }.toMap()
        return AgentRuntimeProfileMigrationResult(
            profiles = profiles,
            createdAgentKeys = created,
            requiresServerSelection = requiresSelection,
            boundManagedServerIds = bound,
            alreadyHadProfiles = existingByKey.keys intersect requestedKeys.toSet()
        )
    }

    private sealed interface LegacyServerBinding {
        data object Unneeded : LegacyServerBinding
        data object Ambiguous : LegacyServerBinding
        data class Bound(val id: Long) : LegacyServerBinding
    }

    private fun resolveLegacyServerBinding(
        url: String?,
        servers: List<ManagedLlamaServerDescriptor>,
        backend: AgentRuntimeBackend
    ): LegacyServerBinding {
        val parsed = runCatching { URI(url?.trim().orEmpty()) }.getOrNull()
        val port = parsed?.port?.takeIf { it in 1..65535 } ?: return LegacyServerBinding.Ambiguous
        val candidates = servers.filter {
            it.port == port && it.normalizedBackend == backend.id
        }
        if (candidates.isEmpty()) return LegacyServerBinding.Ambiguous
        val host = parsed.host?.trim()?.lowercase(Locale.US).orEmpty()
        val exactHost = candidates.filter {
            it.host.trim().lowercase(Locale.US) == host
        }
        return when {
            exactHost.size == 1 -> LegacyServerBinding.Bound(exactHost.single().id)
            candidates.size == 1 -> LegacyServerBinding.Bound(candidates.single().id)
            else -> LegacyServerBinding.Ambiguous
        }
    }

    private fun lookupLegacyModel(values: Map<String, String?>, key: String): String? =
        values[key]
            ?: values.entries.firstOrNull {
                it.key.trim().equals(key.trim(), ignoreCase = true)
            }?.value
}

/** UI/storage abstraction used by built-in and custom Agent editors. */
interface AgentRuntimeProfileStore {
    fun observeProfiles(): Flow<List<AgentRuntimeProfile>>
    suspend fun save(profile: AgentRuntimeProfile)
    suspend fun delete(agentKey: String)
    fun observeEndpointConfigs(): Flow<List<AgentRuntimeEndpointConfig>> = flowOf(emptyList())
    suspend fun saveEndpointConfig(config: AgentRuntimeEndpointConfig): AgentRuntimeEndpointConfig = config
    suspend fun deleteEndpointConfig(id: Long) = Unit
}

object EmptyAgentRuntimeProfileStore : AgentRuntimeProfileStore {
    override fun observeProfiles(): Flow<List<AgentRuntimeProfile>> = flowOf(emptyList())
    override suspend fun save(profile: AgentRuntimeProfile) = Unit
    override suspend fun delete(agentKey: String) = Unit
}

/** Repository facade. Database registration and migration marker wiring stay with the parent. */
class AgentRuntimeProfileRepository(
    private val dao: AgentRuntimeProfileDao,
    private val endpointDao: AgentRuntimeEndpointConfigDao,
    val managedServerCatalog: ManagedLlamaServerCatalog = EmptyManagedLlamaServerCatalog,
    private val migrationMarker: AgentRuntimeProfileMigrationMarker = InMemoryAgentRuntimeProfileMigrationMarker()
) : AgentRuntimeProfileStore {
    override fun observeProfiles(): Flow<List<AgentRuntimeProfile>> =
        dao.observeAll().map { rows -> rows.map { it.toProfile() } }

    fun observeProfile(agentKey: String): Flow<AgentRuntimeProfile?> =
        dao.observe(agentKey).map { it?.toProfile() }

    suspend fun get(agentKey: String): AgentRuntimeProfile? = dao.get(agentKey)?.toProfile()

    override suspend fun save(profile: AgentRuntimeProfile) {
        dao.upsert(AgentRuntimeProfileEntity.fromProfile(profile.normalized()))
    }

    override suspend fun delete(agentKey: String) {
        dao.deleteByAgentKey(agentKey)
    }

    override fun observeEndpointConfigs(): Flow<List<AgentRuntimeEndpointConfig>> =
        endpointDao.observeAll().map { rows -> rows.map { it.toConfig() } }

    suspend fun getEndpointConfig(id: Long): AgentRuntimeEndpointConfig? =
        endpointDao.get(id)?.toConfig()

    override suspend fun saveEndpointConfig(config: AgentRuntimeEndpointConfig): AgentRuntimeEndpointConfig {
        val normalized = config.validate()
        val persistedId = endpointDao.upsert(AgentRuntimeEndpointConfigEntity.fromConfig(normalized))
        return normalized.copy(id = if (normalized.id == 0L) persistedId else normalized.id)
    }

    override suspend fun deleteEndpointConfig(id: Long) {
        // Repair references before deleting the row. Remote agents then fail
        // closed through the existing Needs direction path rather than using a
        // different global endpoint by accident.
        dao.clearEndpointConfigReferences(id, System.currentTimeMillis())
        endpointDao.deleteById(id)
    }

    suspend fun saveAll(profiles: Collection<AgentRuntimeProfile>) {
        dao.upsertAll(profiles.map { AgentRuntimeProfileEntity.fromProfile(it.normalized()) })
    }

    suspend fun migrateOnce(
        customAgentNames: Collection<String>,
        legacy: LegacyAgentRuntimeSettings,
        builtInAgentKeys: Collection<String> = AgentRuntimeProfileKeys.builtIn,
        now: Long = System.currentTimeMillis()
    ): AgentRuntimeProfileMigrationResult {
        if (migrationMarker.isComplete()) {
            val current = dao.getAll().map { it.toProfile() }
            return AgentRuntimeProfileMigrationResult(
                profiles = current,
                createdAgentKeys = emptySet(),
                requiresServerSelection = emptySet(),
                boundManagedServerIds = current.mapNotNull {
                    it.managedLlamaServerId?.let { serverId -> it.agentKey to serverId }
                }.toMap(),
                alreadyHadProfiles = current.map { it.agentKey }.toSet()
            )
        }
        val current = dao.getAll().map { it.toProfile() }
        val servers = managedServerCatalog.observeServers().first()
        val result = AgentRuntimeProfileMigration.migrate(
            builtInAgentKeys = builtInAgentKeys,
            customAgentNames = customAgentNames,
            legacy = legacy,
            managedServers = servers,
            existingProfiles = current,
            now = now
        )
        val missing = result.profiles.filter { profile -> current.none { it.agentKey == profile.agentKey } }
        if (missing.isNotEmpty()) dao.upsertAll(missing.map(AgentRuntimeProfileEntity::fromProfile))
        migrationMarker.markComplete()
        return result
    }

    /** Capture a profile and resolve the assigned resources without starting anything. */
    suspend fun resolveForDispatch(
        agentKey: String,
        liteRtModelCatalog: AgentLiteRtModelCatalog = EmptyAgentLiteRtModelCatalog,
        globalOverride: AgentRuntimeGlobalOverride? = null
    ): AgentRuntimeDispatch {
        val storedProfile = get(agentKey)
        val profile = when {
            storedProfile != null -> globalOverride?.applyTo(storedProfile) ?: storedProfile
            globalOverride?.enabled == true -> AgentRuntimeProfile(
                agentKey = agentKey,
                backend = globalOverride.backend,
                model = globalOverride.model,
                endpointConfigId = globalOverride.endpointConfigId,
                managedLlamaServerId = globalOverride.managedLlamaServerId,
                liteRtModelId = globalOverride.liteRtModelId
            ).normalized()
            else -> return AgentRuntimeDispatch.NeedsDirection(
                agentKey = agentKey,
                profile = null,
                reason = AgentRuntimeNeedsDirectionReason.PROFILE_MISSING,
                continueAction = AgentRuntimeContinueAction.openProfile(agentKey)
            )
        }
        val endpoint = profile.endpointConfigId?.let { endpointDao.get(it)?.toConfig() }
        if (profile.endpointConfigId != null && endpoint == null) {
            return AgentRuntimeDispatch.NeedsDirection(
                agentKey = profile.agentKey,
                profile = profile,
                reason = AgentRuntimeNeedsDirectionReason.ENDPOINT_MISSING,
                continueAction = AgentRuntimeContinueAction.openProfile(profile.agentKey)
            )
        }
        val effectiveProfile = if (endpoint != null) {
            profile.copy(
                backend = endpoint.backend,
                model = profile.model?.takeIf { it.isNotBlank() } ?: endpoint.defaultModel,
                // A named URL is authoritative. Managed local cards remain
                // supported for profiles without a named endpoint.
                managedLlamaServerId = if (endpoint.baseUrl.isNotBlank()) null else profile.managedLlamaServerId
            ).normalized()
        } else {
            profile
        }
        val server = effectiveProfile.managedLlamaServerId?.let { managedServerCatalog.getServer(it) }
        return AgentRuntimeDispatchResolver.resolve(effectiveProfile, server, liteRtModelCatalog, endpoint)
    }
}

interface AgentRuntimeProfileMigrationMarker {
    suspend fun isComplete(): Boolean
    suspend fun markComplete()
}

class InMemoryAgentRuntimeProfileMigrationMarker : AgentRuntimeProfileMigrationMarker {
    private var complete = false
    override suspend fun isComplete(): Boolean = complete
    override suspend fun markComplete() { complete = true }
}

/** Durable marker implementation for callers that already own a SharedPreferences namespace. */
class SharedPreferencesAgentRuntimeProfileMigrationMarker(
    private val preferences: android.content.SharedPreferences,
    private val key: String = "agent_runtime_profiles_migrated_v1"
) : AgentRuntimeProfileMigrationMarker {
    override suspend fun isComplete(): Boolean = preferences.getBoolean(key, false)
    override suspend fun markComplete() { preferences.edit().putBoolean(key, true).apply() }
}

enum class AgentRuntimeNeedsDirectionReason {
    PROFILE_MISSING,
    ENDPOINT_MISSING,
    SERVER_MISSING,
    SERVER_STOPPED,
    SERVER_NOT_READY,
    LITERT_MODEL_MISSING,
    MODEL_MISSING
}

data class AgentRuntimeContinueAction(
    val destination: String,
    val agentKey: String,
    val managedLlamaServerId: Long? = null
) {
    companion object {
        fun openProfile(agentKey: String) = AgentRuntimeContinueAction(
            destination = "agent_runtime_profile",
            agentKey = agentKey
        )

        fun openServer(agentKey: String, serverId: Long?) = AgentRuntimeContinueAction(
            destination = "managed_llama_server",
            agentKey = agentKey,
            managedLlamaServerId = serverId
        )
    }
}

sealed interface AgentRuntimeDispatch {
    val agentKey: String

    data class Ready(
        override val agentKey: String,
        val profile: AgentRuntimeProfile,
        val managedServer: ManagedLlamaServerDescriptor? = null,
        val endpointConfig: AgentRuntimeEndpointConfig? = null
    ) : AgentRuntimeDispatch

    data class NeedsDirection(
        override val agentKey: String,
        val profile: AgentRuntimeProfile?,
        val reason: AgentRuntimeNeedsDirectionReason,
        val continueAction: AgentRuntimeContinueAction
    ) : AgentRuntimeDispatch
}

/** Pure dispatch guard used by root turns, delegated turns, and summarizer calls. */
object AgentRuntimeDispatchResolver {
    suspend fun resolve(
        profile: AgentRuntimeProfile,
        managedServer: ManagedLlamaServerDescriptor?,
        liteRtModelCatalog: AgentLiteRtModelCatalog = EmptyAgentLiteRtModelCatalog,
        endpointConfig: AgentRuntimeEndpointConfig? = null,
        globalOverride: AgentRuntimeGlobalOverride? = null
    ): AgentRuntimeDispatch {
        val normalized = (globalOverride?.applyTo(profile) ?: profile).normalized()
        val endpoint = endpointConfig?.normalized()
        if (endpoint != null && runCatching { endpoint.validate() }.isFailure) {
            return AgentRuntimeDispatch.NeedsDirection(
                normalized.agentKey,
                normalized,
                AgentRuntimeNeedsDirectionReason.ENDPOINT_MISSING,
                AgentRuntimeContinueAction.openProfile(normalized.agentKey)
            )
        }
        val hasNamedEndpoint = endpoint != null && endpoint.baseUrl.isNotBlank()
        val managedEndpointValid = managedServer == null || managedServer.baseUrl != null
        return when (normalized.normalizedBackend) {
            AgentRuntimeBackend.OLLAMA -> {
                if ((!hasNamedEndpoint && endpoint != null) || normalized.model.isNullOrBlank()) {
                    AgentRuntimeDispatch.NeedsDirection(
                        normalized.agentKey,
                        normalized,
                        if (!hasNamedEndpoint && endpoint != null) {
                            AgentRuntimeNeedsDirectionReason.ENDPOINT_MISSING
                        } else {
                            AgentRuntimeNeedsDirectionReason.MODEL_MISSING
                        },
                        AgentRuntimeContinueAction.openProfile(normalized.agentKey)
                    )
                } else {
                    AgentRuntimeDispatch.Ready(normalized.agentKey, normalized, managedServer, endpoint)
                }
            }
            AgentRuntimeBackend.LLAMA_SWAP -> when {
                normalized.model.isNullOrBlank() -> AgentRuntimeDispatch.NeedsDirection(
                    normalized.agentKey,
                    normalized,
                    AgentRuntimeNeedsDirectionReason.MODEL_MISSING,
                    AgentRuntimeContinueAction.openProfile(normalized.agentKey)
                )
                endpoint != null && !hasNamedEndpoint -> AgentRuntimeDispatch.NeedsDirection(
                    normalized.agentKey,
                    normalized,
                    AgentRuntimeNeedsDirectionReason.ENDPOINT_MISSING,
                    AgentRuntimeContinueAction.openProfile(normalized.agentKey)
                )
                normalized.managedLlamaServerId != null && managedServer == null -> AgentRuntimeDispatch.NeedsDirection(
                    normalized.agentKey,
                    normalized,
                    AgentRuntimeNeedsDirectionReason.SERVER_MISSING,
                    AgentRuntimeContinueAction.openServer(normalized.agentKey, normalized.managedLlamaServerId)
                )
                normalized.managedLlamaServerId != null && managedServer?.normalizedBackend != AgentRuntimeBackend.LLAMA_SWAP.id -> AgentRuntimeDispatch.NeedsDirection(
                    normalized.agentKey,
                    normalized,
                    AgentRuntimeNeedsDirectionReason.SERVER_MISSING,
                    AgentRuntimeContinueAction.openServer(normalized.agentKey, normalized.managedLlamaServerId)
                )
                normalized.managedLlamaServerId != null && managedServer != null && !managedServer.isReady -> AgentRuntimeDispatch.NeedsDirection(
                    normalized.agentKey,
                    normalized,
                    if (managedServer.state == ManagedLlamaServerState.STOPPED) {
                        AgentRuntimeNeedsDirectionReason.SERVER_STOPPED
                    } else {
                        AgentRuntimeNeedsDirectionReason.SERVER_NOT_READY
                    },
                    AgentRuntimeContinueAction.openServer(normalized.agentKey, managedServer.id)
                )
                normalized.managedLlamaServerId != null && !managedEndpointValid -> AgentRuntimeDispatch.NeedsDirection(
                    normalized.agentKey,
                    normalized,
                    AgentRuntimeNeedsDirectionReason.SERVER_NOT_READY,
                    AgentRuntimeContinueAction.openServer(normalized.agentKey, managedServer?.id)
                )
                else -> AgentRuntimeDispatch.Ready(normalized.agentKey, normalized, managedServer, endpoint)
            }
            AgentRuntimeBackend.LLAMA_SERVER -> when {
                hasNamedEndpoint -> AgentRuntimeDispatch.Ready(normalized.agentKey, normalized, managedServer, endpoint)
                // No named endpoint and no managed-card id means the explicit global
                // llama-server connection. A non-null id (including the UI's zero
                // "choose a card" marker) remains fail-closed as a managed target.
                normalized.managedLlamaServerId == null ->
                    AgentRuntimeDispatch.Ready(normalized.agentKey, normalized, null, null)
                managedServer == null -> AgentRuntimeDispatch.NeedsDirection(
                    normalized.agentKey,
                    normalized,
                    AgentRuntimeNeedsDirectionReason.SERVER_MISSING,
                    AgentRuntimeContinueAction.openServer(normalized.agentKey, normalized.managedLlamaServerId)
                )
                managedServer.normalizedBackend != AgentRuntimeBackend.LLAMA_SERVER.id -> AgentRuntimeDispatch.NeedsDirection(
                    normalized.agentKey,
                    normalized,
                    AgentRuntimeNeedsDirectionReason.SERVER_MISSING,
                    AgentRuntimeContinueAction.openServer(normalized.agentKey, normalized.managedLlamaServerId)
                )
                !managedServer.isReady -> AgentRuntimeDispatch.NeedsDirection(
                    normalized.agentKey,
                    normalized,
                    if (managedServer.state == ManagedLlamaServerState.STOPPED) {
                        AgentRuntimeNeedsDirectionReason.SERVER_STOPPED
                    } else {
                        AgentRuntimeNeedsDirectionReason.SERVER_NOT_READY
                    },
                    AgentRuntimeContinueAction.openServer(normalized.agentKey, managedServer.id)
                )
                !managedEndpointValid -> AgentRuntimeDispatch.NeedsDirection(
                    normalized.agentKey,
                    normalized,
                    AgentRuntimeNeedsDirectionReason.SERVER_NOT_READY,
                    AgentRuntimeContinueAction.openServer(normalized.agentKey, managedServer.id)
                )
                else -> AgentRuntimeDispatch.Ready(normalized.agentKey, normalized, managedServer, endpoint)
            }
            AgentRuntimeBackend.LITERT -> when {
                normalized.liteRtModelId == null || normalized.liteRtModelId <= 0L -> AgentRuntimeDispatch.NeedsDirection(
                    normalized.agentKey,
                    normalized,
                    AgentRuntimeNeedsDirectionReason.LITERT_MODEL_MISSING,
                    AgentRuntimeContinueAction.openProfile(normalized.agentKey)
                )
                !liteRtModelCatalog.containsModel(normalized.liteRtModelId) -> AgentRuntimeDispatch.NeedsDirection(
                    normalized.agentKey,
                    normalized,
                    AgentRuntimeNeedsDirectionReason.LITERT_MODEL_MISSING,
                    AgentRuntimeContinueAction.openProfile(normalized.agentKey)
                )
                else -> AgentRuntimeDispatch.Ready(normalized.agentKey, normalized, managedServer, endpoint)
            }
        }
    }
}
