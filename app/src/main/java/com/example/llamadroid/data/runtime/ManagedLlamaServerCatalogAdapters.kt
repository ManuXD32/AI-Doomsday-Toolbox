package com.example.llamadroid.data.runtime

import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.repository.LlamaServerCardRepository
import com.example.llamadroid.service.LlamaServerSessionRuntime
import com.example.llamadroid.service.LlamaServerSessionSnapshot
import com.example.llamadroid.service.LlamaServerSessionStateStore
import com.example.llamadroid.service.LlamaServerSessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive

/** Map the sibling session state to the profile layer's read-only status. */
fun LlamaServerSessionStatus.toManagedLlamaServerState(): ManagedLlamaServerState = when (this) {
    LlamaServerSessionStatus.RUNNING -> ManagedLlamaServerState.RUNNING
    LlamaServerSessionStatus.STARTING -> ManagedLlamaServerState.STARTING
    LlamaServerSessionStatus.LOADING -> ManagedLlamaServerState.LOADING
    LlamaServerSessionStatus.STOPPED -> ManagedLlamaServerState.STOPPED
    LlamaServerSessionStatus.ERROR -> ManagedLlamaServerState.ERROR
}

/**
 * Adapter for the sibling card/session subsystem.  It observes cards and the
 * runtime's StateFlow but deliberately exposes no start/stop capability to
 * Agent dispatch.  [hostForCard] may point at a local or remote managed host;
 * it defaults to loopback because cards own a local native child by design.
 */
class LlamaServerCardManagedServerCatalog(
    private val cards: Flow<List<LlamaServerCardEntity>>,
    private val sessions: Flow<Map<String, LlamaServerSessionSnapshot>>,
    private val modelNames: Flow<Map<Long, String?>> = flowOf(emptyMap()),
    private val hostForCard: (LlamaServerCardEntity) -> String = { "127.0.0.1" }
) : ManagedLlamaServerCatalog {
    constructor(
        repository: LlamaServerCardRepository,
        runtime: LlamaServerSessionRuntime,
        hostForCard: (LlamaServerCardEntity) -> String = { "127.0.0.1" }
    ) : this(repository.cards, runtime.snapshots, hostForCard = hostForCard)

    override fun observeServers(): Flow<List<ManagedLlamaServerDescriptor>> =
        combine(cards, sessions, modelNames) { cardRows, sessionRows, modelRows ->
            cardRows.map { card ->
                card.toManagedLlamaServerDescriptor(
                    snapshot = sessionRows[card.sessionId],
                    host = hostForCard(card),
                    modelName = modelRows[card.id]
                )
            }
        }

    override suspend fun getServer(id: Long): ManagedLlamaServerDescriptor? =
        observeServers().first().firstOrNull { it.id == id }
}

/**
 * Adapter for callers that only have the durable metadata store.  State will
 * refresh whenever the card stream emits; the live runtime constructor above
 * should be preferred for immediate start/stop visibility.
 */
class DurableLlamaServerCardCatalog(
    cards: Flow<List<LlamaServerCardEntity>>,
    stateStore: LlamaServerSessionStateStore,
    modelNames: Flow<Map<Long, String?>> = flowOf(emptyMap()),
    hostForCard: (LlamaServerCardEntity) -> String = { "127.0.0.1" },
    refreshIntervalMs: Long = 750L
) : ManagedLlamaServerCatalog {
    private val delegate = LlamaServerCardManagedServerCatalog(
        cards = cards,
        sessions = flow {
            while (currentCoroutineContext().isActive) {
                emit(stateStore.readAll().associateBy(LlamaServerSessionSnapshot::sessionId))
                delay(refreshIntervalMs)
            }
        },
        modelNames = modelNames,
        hostForCard = hostForCard
    )

    override fun observeServers(): Flow<List<ManagedLlamaServerDescriptor>> = delegate.observeServers()

    override suspend fun getServer(id: Long): ManagedLlamaServerDescriptor? = delegate.getServer(id)
}

fun LlamaServerCardEntity.toManagedLlamaServerDescriptor(
    snapshot: LlamaServerSessionSnapshot?,
    host: String = "127.0.0.1",
    modelName: String? = null
): ManagedLlamaServerDescriptor = ManagedLlamaServerDescriptor(
    id = id,
    displayName = name,
    host = host,
    port = snapshot?.port ?: port,
    modelName = modelName,
    state = snapshot?.status?.toManagedLlamaServerState()
        ?: ManagedLlamaServerState.STOPPED
)
