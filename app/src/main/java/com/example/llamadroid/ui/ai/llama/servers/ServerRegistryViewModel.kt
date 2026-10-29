package com.example.llamadroid.ui.ai.llama.servers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.SavedCommand
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.data.db.launchProfile
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.LlamaServerRegistry
import com.example.llamadroid.data.repository.LocalLlamaServerRepository
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.data.repository.UnifiedServerStatus
import com.example.llamadroid.data.repository.easyLlamaProfile
import com.example.llamadroid.data.repository.normalizedManagedToolDefaults
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaServerSessionLogStore
import com.example.llamadroid.service.ManagedLlamaServerException
import com.example.llamadroid.service.NativeChatServerStartException
import com.example.llamadroid.util.GGUFParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Stable identity of a server across the card/row merge; used to key transient UI state. */
val UnifiedServer.key: String
    get() = when {
        cardId != null -> "card:$cardId"
        rowId != null -> "row:$rowId"
        else -> "anon:$name:$port"
    }

/** Transient, per-server action state: live progress text and the last localized error. */
data class ServerActionState(
    val busy: Boolean = false,
    val progress: String? = null,
    val error: String? = null
)

/**
 * Single state holder for the unified server UI: the Servers screen, the strip card, the detail
 * sheet, the editor and the picker all observe this and call these actions, so status, progress
 * and errors are identical everywhere. Exceptions become localized, user-visible text; nothing
 * is thrown into the UI.
 */
class ServerRegistryViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application = application
    private val database = AppDatabase.getDatabase(app)
    private val registry = LlamaServerRegistry(app, database)
    private val localRepository = LocalLlamaServerRepository(app, database)
    private val logStore = LlamaServerSessionLogStore(app)
    private val jobs = HashMap<String, Job>()

    val servers: StateFlow<List<UnifiedServer>> = registry.observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * Every server including Easy-mode ones. Only for resolving the server a chat is already bound
     * to (status banner, start/stop); pickers and lists keep using [servers].
     */
    val serversIncludingEasy: StateFlow<List<UnifiedServer>> = registry.observeServers(includeEasy = true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _actions = MutableStateFlow<Map<String, ServerActionState>>(emptyMap())

    /** Transient status (progress / error) per [key]; missing key means idle with no error. */
    val actions: StateFlow<Map<String, ServerActionState>> = _actions.asStateFlow()

    /** GENERAL presets, offered by the editor's "Start from a saved preset". */
    val generalPresets: Flow<List<SavedCommand>> =
        database.savedCommandDao().getCommandsByScope(SavedCommandScopes.GENERAL)

    private fun setAction(key: String, state: ServerActionState?) = _actions.update { current ->
        if (state == null) current - key else current + (key to state)
    }

    fun clearError(server: UnifiedServer) {
        val current = _actions.value[server.key] ?: return
        if (!current.busy) setAction(server.key, null) else setAction(server.key, current.copy(error = null))
    }

    private fun failureText(throwable: Throwable, fallbackRes: Int): String =
        (throwable as? ManagedLlamaServerException)?.message?.takeIf { it.isNotBlank() }
            ?: (throwable as? NativeChatServerStartException)?.message?.takeIf { it.isNotBlank() }
            ?: app.getString(fallbackRes)

    private fun runAction(server: UnifiedServer, fallbackRes: Int, block: suspend (onStatus: (String) -> Unit) -> Unit) {
        val key = server.key
        if (_actions.value[key]?.busy == true) return
        setAction(key, ServerActionState(busy = true, progress = app.getString(R.string.managed_llama_starting)))
        jobs[key] = viewModelScope.launch {
            try {
                block { message ->
                    _actions.update { map ->
                        val current = map[key] ?: return@update map
                        if (current.busy) map + (key to current.copy(progress = message)) else map
                    }
                }
                setAction(key, null)
            } catch (cancel: CancellationException) {
                setAction(key, null)
                throw cancel
            } catch (throwable: Throwable) {
                setAction(key, ServerActionState(error = failureText(throwable, fallbackRes)))
            } finally {
                jobs.remove(key)
            }
        }
    }

    /** Start a local server; the progress text arrives through [actions]. Remote servers are ignored. */
    fun start(server: UnifiedServer) {
        if (server.kind == UnifiedServerKind.REMOTE) return
        runAction(server, R.string.managed_llama_start_failed) { onStatus -> registry.start(server, onStatus) }
    }

    fun restart(server: UnifiedServer) {
        if (server.kind == UnifiedServerKind.REMOTE) return
        runAction(server, R.string.managed_llama_start_failed) { onStatus -> registry.restart(server, onStatus) }
    }

    /** Stop (or cancel an in-flight start of) a local server. */
    fun stop(server: UnifiedServer) {
        if (server.kind == UnifiedServerKind.REMOTE) return
        jobs.remove(server.key)?.cancel()
        setAction(server.key, null)
        viewModelScope.launch {
            try {
                registry.stop(server)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                setAction(server.key, ServerActionState(error = failureText(throwable, R.string.srv_action_failed)))
            }
        }
    }

    /** Suspends until the server is deleted; failures are surfaced through [actions]. */
    suspend fun delete(server: UnifiedServer): Boolean {
        jobs.remove(server.key)?.cancel()
        return try {
            registry.delete(server)
            setAction(server.key, null)
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            setAction(server.key, ServerActionState(error = failureText(throwable, R.string.srv_action_failed)))
            false
        }
    }

    /** Upgrade a legacy local row to a managed server. Returns true on success. */
    suspend fun convert(server: UnifiedServer): Boolean = try {
        registry.convert(server) != null
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (throwable: Throwable) {
        setAction(server.key, ServerActionState(error = failureText(throwable, R.string.srv_action_failed)))
        false
    }

    fun setAllowWearStart(server: UnifiedServer, enabled: Boolean) {
        val cardId = server.cardId ?: return
        viewModelScope.launch {
            try {
                // The DAO keeps exactly one watch-startable card; enabling one clears the others.
                withContext(Dispatchers.IO) {
                    database.llamaServerCardDao().setWearStartCard(if (enabled) cardId else null)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                setAction(server.key, ServerActionState(error = failureText(throwable, R.string.srv_action_failed)))
            }
        }
    }

    // Chats and logs -----------------------------------------------------------------------

    suspend fun boundChats(server: UnifiedServer): List<LlamaChatEntity> {
        val rowId = server.rowId ?: return emptyList()
        return withContext(Dispatchers.IO) { database.llamaChatDao().getChatsBoundToServer(rowId) }
    }

    /** Bounded tail of the managed session log; legacy and remote servers have none. */
    suspend fun readLogs(server: UnifiedServer): List<String> {
        val cardId = server.cardId?.takeIf { server.kind == UnifiedServerKind.LOCAL_MANAGED } ?: return emptyList()
        return withContext(Dispatchers.IO) { logStore.read(LlamaServerCardEntity.sessionIdForCard(cardId)) }
    }

    fun clearLogs(server: UnifiedServer) {
        val cardId = server.cardId ?: return
        LlamaServerLauncher.clearSessionLogs(app, LlamaServerCardEntity.sessionIdForCard(cardId))
    }

    // Editor support -----------------------------------------------------------------------

    /** Starting profile for a freshly picked model (context from GGUF metadata, mmproj when present). */
    suspend fun baseProfileFor(model: ModelEntity): LlamaServerLaunchProfile = withContext(Dispatchers.IO) {
        val metadata = GGUFParser.readModelInfo(model.path)
        val knownContext = metadata?.contextLength?.takeIf { metadata.contextLengthDetected }
        easyLlamaProfile(model, port = 0, knownContext = knownContext,
            processors = Runtime.getRuntime().availableProcessors())
    }

    /** [base] with [path]'s model and its detected mmproj/vision/context defaults, when the model is catalogued. */
    suspend fun profileForModelPath(base: LlamaServerLaunchProfile, path: String): LlamaServerLaunchProfile {
        val model = withContext(Dispatchers.IO) { database.modelDao().getModelByPath(path) }
            ?: return base.copy(modelPath = path)
        val detected = baseProfileFor(model)
        return base.copy(
            modelPath = path,
            mmprojPath = detected.mmprojPath,
            visionEnabled = detected.visionEnabled,
            contextSize = detected.contextSize
        )
    }

    /** Profile and card facts needed to edit a managed server, or null when it cannot be edited. */
    suspend fun loadManagedForEdit(server: UnifiedServer): ManagedEditSeed? =
        server.cardId?.let { loadManagedSeed(it) }

    /** Same as [loadManagedForEdit] when only the card id is known (the full-screen editor route). */
    suspend fun loadManagedSeed(cardId: Long): ManagedEditSeed? = withContext(Dispatchers.IO) {
        val card = database.llamaServerCardDao().getCard(cardId) ?: return@withContext null
        val preset = database.savedCommandDao().getGeneralCommandById(card.savedCommandId)
            ?: return@withContext null
        ManagedEditSeed(card.name, card.port, card.allowWearStart, preset.launchProfile())
    }

    /** Tool defaults for new chats of a managed server (legacy all-true values read as all tools off). */
    suspend fun loadManagedToolDefaults(cardId: Long): String = withContext(Dispatchers.IO) {
        normalizedManagedToolDefaults(database.llamaServerDao().getManagedServer(cardId)?.defaultApiParams)
    }

    /** Remote row for the remote half of the editor. */
    suspend fun loadRow(server: UnifiedServer): LlamaServerEntity? = withContext(Dispatchers.IO) {
        server.rowId?.let { database.llamaServerDao().getServerById(it) }
    }

    /**
     * Create a managed local server and optionally start it. The success value is the new
     * connection row id; the failure message is already localized for inline display.
     */
    suspend fun createLocal(
        name: String,
        profile: LlamaServerLaunchProfile,
        port: Int?,
        allowWearStart: Boolean,
        startAfterCreate: Boolean,
        defaultApiParams: String? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val ids = localRepository.createLocalServer(
                name, profile, port = port, allowWearStart = allowWearStart, defaultApiParams = defaultApiParams)
            if (startAfterCreate) {
                val card = database.llamaServerCardDao().getCard(ids.cardId)
                start(UnifiedServer(
                    rowId = ids.serverRowId, cardId = ids.cardId, kind = UnifiedServerKind.LOCAL_MANAGED,
                    name = card?.name ?: name, engine = LlamaServerEntity.ENGINE_LLAMA_SERVER,
                    modelName = profile.modelPath.substringAfterLast('/'), host = "127.0.0.1",
                    port = card?.port ?: profile.serverPort, status = UnifiedServerStatus.STOPPED,
                    errorCode = null, boundChatCount = 0, allowWearStart = allowWearStart, isEasy = false
                ))
            }
            Result.success(ids.serverRowId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            Result.failure<Long>(IllegalStateException(failureText(throwable, R.string.srv_save_failed), throwable))
        }
    }

    suspend fun updateLocal(
        cardId: Long,
        profile: LlamaServerLaunchProfile,
        name: String,
        port: Int?,
        allowWearStart: Boolean,
        defaultApiParams: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            localRepository.updateLocalServerProfile(
                cardId, profile, name = name, port = port, allowWearStart = allowWearStart,
                defaultApiParams = defaultApiParams)
            Result.success(Unit)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            Result.failure<Unit>(IllegalStateException(failureText(throwable, R.string.srv_save_failed), throwable))
        }
    }

    /** Make sure a managed server has a connection row so a chat can bind to it. */
    suspend fun ensureRowId(server: UnifiedServer): Long? {
        server.rowId?.let { return it }
        val cardId = server.cardId ?: return null
        return withContext(Dispatchers.IO) {
            try {
                localRepository.ensureLinkedRow(cardId)?.id
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                setAction(server.key, ServerActionState(error = failureText(throwable, R.string.srv_action_failed)))
                null
            }
        }
    }

    /** Whether a model file path is readable; kept here so the UI never touches files directly. */
    fun fileReadable(path: String): Boolean = File(path).let { it.isFile && it.canRead() }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** What the editor needs to prefill an existing managed server. */
data class ManagedEditSeed(
    val name: String,
    val port: Int,
    val allowWearStart: Boolean,
    val profile: LlamaServerLaunchProfile
)
