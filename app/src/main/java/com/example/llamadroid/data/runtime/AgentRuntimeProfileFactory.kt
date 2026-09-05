package com.example.llamadroid.data.runtime

import android.content.Context
import com.example.llamadroid.data.db.AgentRuntimeEndpointConfigDao
import com.example.llamadroid.data.db.AgentRuntimeProfileDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central integration seam for the profile table.  AppDatabase owns the Room
 * registration and schema migration; callers only need to pass its DAO here
 * after those changes land.
 */
object AgentRuntimeProfileRepositoryFactory {
    fun create(
        context: Context,
        dao: AgentRuntimeProfileDao,
        endpointDao: AgentRuntimeEndpointConfigDao,
        managedServerCatalog: ManagedLlamaServerCatalog = EmptyManagedLlamaServerCatalog,
        migrationPreferenceName: String = "agent_runtime_profiles"
    ): AgentRuntimeProfileRepository {
        val preferences = context.applicationContext.getSharedPreferences(
            migrationPreferenceName,
            Context.MODE_PRIVATE
        )
        return AgentRuntimeProfileRepository(
            dao = dao,
            endpointDao = endpointDao,
            managedServerCatalog = managedServerCatalog,
            migrationMarker = SharedPreferencesAgentRuntimeProfileMigrationMarker(
                preferences = preferences
            )
        )
    }
}

/**
 * Optional provider boundary for the central database.  This lets the app
 * install the repository without making the profile module edit AppDatabase.
 */
fun interface AgentRuntimeProfileDaoProvider {
    fun agentRuntimeProfileDao(): AgentRuntimeProfileDao
}

/**
 * Process-wide dispatch installation point.  The service remains usable in
 * previews and during an incremental database rollout when no repository has
 * been installed; once installed, every dispatch captures a repository-backed
 * immutable profile snapshot before selecting a backend.
 */
object AgentRuntimeProfileRuntime {
    @Volatile
    private var repository: AgentRuntimeProfileRepository? = null

    private val _repositoryState = MutableStateFlow<AgentRuntimeProfileRepository?>(null)
    val repositoryState: StateFlow<AgentRuntimeProfileRepository?> = _repositoryState.asStateFlow()

    @Volatile
    private var liteRtModelCatalog: AgentLiteRtModelCatalog = EmptyAgentLiteRtModelCatalog

    @Volatile
    private var managedServerCatalog: ManagedLlamaServerCatalog = EmptyManagedLlamaServerCatalog

    fun install(
        repository: AgentRuntimeProfileRepository,
        liteRtModelCatalog: AgentLiteRtModelCatalog = EmptyAgentLiteRtModelCatalog
    ) {
        this.repository = repository
        this._repositoryState.value = repository
        this.liteRtModelCatalog = liteRtModelCatalog
        this.managedServerCatalog = repository.managedServerCatalog
    }

    fun clear() {
        repository = null
        _repositoryState.value = null
        liteRtModelCatalog = EmptyAgentLiteRtModelCatalog
        managedServerCatalog = EmptyManagedLlamaServerCatalog
    }

    suspend fun resolve(
        agentKey: String,
        globalOverride: AgentRuntimeGlobalOverride? = null
    ): AgentRuntimeDispatch? =
        repository?.resolveForDispatch(
            agentKey = agentKey,
            liteRtModelCatalog = liteRtModelCatalog,
            globalOverride = globalOverride
        )

    fun installedRepository(): AgentRuntimeProfileRepository? = repository

    fun installedManagedServerCatalog(): ManagedLlamaServerCatalog = managedServerCatalog
}
