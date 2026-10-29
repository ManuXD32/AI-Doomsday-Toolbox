package com.example.llamadroid.data.repository

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.SavedCommand
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.data.easy.isEasyModeCard
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaServerLauncher
import com.example.llamadroid.service.LlamaServerSessionOwnerStore
import com.example.llamadroid.service.LlamaServerSessionStateStore
import com.example.llamadroid.service.LlamaServerSessionStatus
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.ManagedLlamaServerCoordinator
import com.example.llamadroid.service.NativeChatLocalServerStarter
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.service.isNativeChatLoopbackHost
import com.example.llamadroid.util.NativeProcessCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

enum class UnifiedServerKind { LOCAL_MANAGED, LOCAL_LEGACY, REMOTE }

enum class UnifiedServerStatus { RUNNING, STARTING, STOPPED, ERROR, UNKNOWN }

/** One entry of the single server list shared by every screen, picker, the scheduler and Wear. */
data class UnifiedServer(
    val rowId: Long?,
    val cardId: Long?,
    val kind: UnifiedServerKind,
    val name: String,
    val engine: String,
    val modelName: String?,
    val host: String,
    val port: Int,
    val status: UnifiedServerStatus,
    val errorCode: String?,
    val boundChatCount: Int,
    val allowWearStart: Boolean,
    val isEasy: Boolean
)

/** Cheap, already-liveness-checked runtime view of a managed card session. */
data class ManagedRuntimeView(val status: LlamaServerSessionStatus, val error: String? = null)

internal fun managedStatusOf(view: ManagedRuntimeView?): UnifiedServerStatus = when (view?.status) {
    LlamaServerSessionStatus.RUNNING -> UnifiedServerStatus.RUNNING
    LlamaServerSessionStatus.STARTING, LlamaServerSessionStatus.LOADING -> UnifiedServerStatus.STARTING
    LlamaServerSessionStatus.ERROR -> UnifiedServerStatus.ERROR
    LlamaServerSessionStatus.STOPPED, null -> UnifiedServerStatus.STOPPED
}

/**
 * The legacy global server reports one state for whichever row launched it. Running is matched by
 * port; transitional states belong to [pendingRowId] (the most recently used legacy row).
 */
internal fun legacyStatusOf(
    state: ServerState,
    row: LlamaServerEntity,
    pendingRowId: Long?
): UnifiedServerStatus = when (state) {
    ServerState.Stopped -> UnifiedServerStatus.STOPPED
    is ServerState.Running -> if (state.port == row.port) UnifiedServerStatus.RUNNING else UnifiedServerStatus.STOPPED
    ServerState.Starting, is ServerState.Loading ->
        if (row.id == pendingRowId) UnifiedServerStatus.STARTING else UnifiedServerStatus.STOPPED
    is ServerState.Error ->
        if (row.id == pendingRowId) UnifiedServerStatus.ERROR else UnifiedServerStatus.STOPPED
}

internal fun serverKindOf(row: LlamaServerEntity): UnifiedServerKind = when {
    row.managedServerCardId != null -> UnifiedServerKind.LOCAL_MANAGED
    row.isLlamaServerEngine() && isNativeChatLoopbackHost(row.host) -> UnifiedServerKind.LOCAL_LEGACY
    else -> UnifiedServerKind.REMOTE
}

/** Pure merge of cards, rows, sessions and the legacy state; no I/O so it is unit-testable. */
internal fun mergeUnifiedServers(
    cards: List<LlamaServerCardEntity>,
    rows: List<LlamaServerEntity>,
    profiles: Map<Long, LlamaServerLaunchProfile?>,
    sessions: Map<String, ManagedRuntimeView>,
    legacyState: ServerState,
    boundCounts: Map<Long, Int>,
    modelFilesPresent: (LlamaServerLaunchProfile) -> Boolean = { true }
): List<UnifiedServer> {
    val rowsByCard = rows.filter { it.managedServerCardId != null }.associateBy { it.managedServerCardId!! }
    val cardIds = cards.map { it.id }.toSet()
    val legacyRows = rows.filter { serverKindOf(it) == UnifiedServerKind.LOCAL_LEGACY }
    val pendingLegacy = legacyRows.maxByOrNull { it.lastUsed }?.id
    val result = ArrayList<UnifiedServer>(cards.size + rows.size)
    for (card in cards) {
        val row = rowsByCard[card.id]
        val profile = profiles[card.savedCommandId]
        val view = sessions[card.sessionId]
        val error = when {
            profile == null -> "MANAGED_PRESET_MISSING"
            !modelFilesPresent(profile) -> "MANAGED_MODEL_MISSING"
            else -> null
        }
        val status = if (error != null && managedStatusOf(view) != UnifiedServerStatus.RUNNING)
            UnifiedServerStatus.ERROR else managedStatusOf(view)
        result += UnifiedServer(
            rowId = row?.id, cardId = card.id, kind = UnifiedServerKind.LOCAL_MANAGED,
            name = card.name, engine = row?.engine ?: LlamaServerEntity.ENGINE_LLAMA_SERVER,
            modelName = row?.modelName ?: profile?.modelPath?.substringAfterLast('/'),
            host = row?.host ?: "127.0.0.1", port = row?.port ?: card.port,
            status = status, errorCode = error ?: view?.error?.takeIf { status == UnifiedServerStatus.ERROR },
            boundChatCount = row?.let { boundCounts[it.id] } ?: 0,
            allowWearStart = card.allowWearStart, isEasy = card.easyModelId != null
        )
    }
    for (row in rows) {
        val cardId = row.managedServerCardId
        if (cardId != null && cardId in cardIds) continue // already merged with its card
        val kind = if (cardId != null) UnifiedServerKind.LOCAL_MANAGED else serverKindOf(row)
        val status = when {
            cardId != null -> UnifiedServerStatus.ERROR // card deleted behind the row's back
            kind == UnifiedServerKind.LOCAL_LEGACY -> legacyStatusOf(legacyState, row, pendingLegacy)
            else -> UnifiedServerStatus.UNKNOWN
        }
        result += UnifiedServer(
            rowId = row.id, cardId = cardId, kind = kind, name = row.name, engine = row.normalizedEngine(),
            modelName = row.modelName, host = row.host, port = row.port, status = status,
            errorCode = if (cardId != null) "MANAGED_CARD_MISSING" else null,
            boundChatCount = boundCounts[row.id] ?: 0, allowWearStart = false, isEasy = false
        )
    }
    return result
}

/**
 * Single registry for every llama server the app knows: managed cards (with their linked rows),
 * legacy loopback rows and remote rows. Screens, pickers, Wear Quick Chat and the scheduler use it.
 */
class LlamaServerRegistry(
    context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {
    private val context = context.applicationContext
    private val states = LlamaServerSessionStateStore(this.context)
    private val owners = LlamaServerSessionOwnerStore(this.context)
    private val local = LocalLlamaServerRepository(this.context, database)

    /** Polls the session state files every [POLL_MS] while collected (same source as the card UI). */
    private fun sessionViews(): Flow<Map<String, ManagedRuntimeView>> = flow {
        while (true) {
            emit(readSessions())
            delay(POLL_MS)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun readSessions(): Map<String, ManagedRuntimeView> {
        val liveOwners = owners.readAll().associateBy { it.sessionId }
        return states.readAll().associate { snapshot ->
            val owner = liveOwners[snapshot.sessionId]
            val stale = snapshot.status == LlamaServerSessionStatus.RUNNING && (owner == null ||
                !NativeProcessCleanup.recordedLlamaOwnerIsAliveSync(owner.pid, owner.processStartTimeTicks, owner.port))
            snapshot.sessionId to ManagedRuntimeView(
                if (stale) LlamaServerSessionStatus.STOPPED else snapshot.status, snapshot.error)
        }
    }

    /** Advanced lists use the default; only Easy-mode UI passes [includeEasy] to see its own cards. */
    fun observeServers(includeEasy: Boolean = false): Flow<List<UnifiedServer>> {
        val data = combine(
            database.llamaServerCardDao().observeCards(),
            if (includeEasy) database.llamaServerDao().getAllServersIncludingEasy()
            else database.llamaServerDao().getAllServers(),
            database.savedCommandDao().getCommandsByScope(SavedCommandScopes.GENERAL),
            database.llamaChatDao().observeBoundChatCounts()
        ) { cards, rows, presets, counts -> Snapshot(cards, rows, presets, counts.associate { it.serverId to it.chatCount }) }
        return combine(data, sessionViews(), LlamaService.state) { snap, sessions, legacy ->
            val presetById = snap.presets.associateBy { it.id }
            val profiles = snap.cards.associate { card ->
                card.savedCommandId to presetById[card.savedCommandId]?.launchProfileForCard(card)
            }
            // Easy-mode cards (and the connection rows bound to them) stay out of the advanced list.
            val easyCardIds = if (includeEasy) emptySet() else snap.cards.filter(::isEasyModeCard).map { it.id }.toSet()
            val cards = snap.cards.filterNot { it.id in easyCardIds }
            val rows = snap.rows.filterNot { it.managedServerCardId in easyCardIds }
            mergeUnifiedServers(cards, rows, profiles, sessions, legacy, snap.counts, ::modelFilesPresent)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    private class Snapshot(
        val cards: List<LlamaServerCardEntity>,
        val rows: List<LlamaServerEntity>,
        val presets: List<SavedCommand>,
        val counts: Map<Long, Int>
    )

    private fun modelFilesPresent(profile: LlamaServerLaunchProfile): Boolean =
        File(profile.modelPath).let { it.isFile && it.canRead() } &&
            (!profile.visionEnabled || profile.mmprojPath?.let { File(it).let { f -> f.isFile && f.canRead() } } == true)

    // Actions ------------------------------------------------------------------------------

    suspend fun start(server: UnifiedServer, onStatus: (String) -> Unit = {}) {
        when (server.kind) {
            UnifiedServerKind.LOCAL_MANAGED -> {
                val cardId = requireNotNull(server.cardId) { "Managed server has no card." }
                ManagedLlamaServerCoordinator(context, database).prepare(cardId, onStatus)
            }
            UnifiedServerKind.LOCAL_LEGACY -> {
                val row = database.llamaServerDao().getServerById(requireNotNull(server.rowId)) ?: return
                NativeChatLocalServerStarter(context).ensureReady(row, onStatus = onStatus)
            }
            UnifiedServerKind.REMOTE -> Unit
        }
    }

    suspend fun stop(server: UnifiedServer) {
        when (server.kind) {
            UnifiedServerKind.LOCAL_MANAGED -> server.cardId?.let {
                LlamaServerLauncher.stopSession(context, LlamaServerCardEntity.sessionIdForCard(it))
            }
            UnifiedServerKind.LOCAL_LEGACY -> LlamaServerLauncher.stopLegacy(context)
            UnifiedServerKind.REMOTE -> Unit
        }
    }

    suspend fun restart(server: UnifiedServer, onStatus: (String) -> Unit = {}) {
        stop(server)
        // Wait (bounded) for the old process to report stopped before launching again.
        withTimeoutOrNull(STOP_WAIT_MS) {
            while (!isStopped(server)) delay(300)
        }
        start(server, onStatus)
    }

    private suspend fun isStopped(server: UnifiedServer): Boolean = withContext(Dispatchers.IO) {
        when (server.kind) {
            UnifiedServerKind.LOCAL_MANAGED -> {
                val sessionId = server.cardId?.let(LlamaServerCardEntity::sessionIdForCard)
                val status = sessionId?.let { readSessions()[it]?.status }
                status == null || status == LlamaServerSessionStatus.STOPPED || status == LlamaServerSessionStatus.ERROR
            }
            UnifiedServerKind.LOCAL_LEGACY -> LlamaService.state.value.let { it is ServerState.Stopped || it is ServerState.Error }
            UnifiedServerKind.REMOTE -> true
        }
    }

    suspend fun delete(server: UnifiedServer) {
        val cardId = server.cardId
        if (server.kind == UnifiedServerKind.LOCAL_MANAGED && cardId != null &&
            database.llamaServerCardDao().getCard(cardId) != null) {
            local.deleteLocalServer(cardId)
            return
        }
        val rowId = server.rowId ?: return
        val row = database.llamaServerDao().getServerById(rowId) ?: return
        database.llamaChatDao().clearChatServerBinding(rowId)
        database.llamaServerDao().deleteServer(row)
    }

    /** Upgrade a legacy loopback row to a managed card + preset. Returns the new ids or null. */
    suspend fun convert(server: UnifiedServer): LocalServerIds? {
        if (server.kind != UnifiedServerKind.LOCAL_LEGACY) return null
        return local.convertLegacyRowToManaged(server.rowId ?: return null)
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val STOP_WAIT_MS = 10_000L
    }
}
