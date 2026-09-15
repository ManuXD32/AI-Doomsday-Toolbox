package com.example.llamadroid.tama.world.runtime

import android.content.Context
import androidx.room.withTransaction
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaCommitEffects
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.tama.game.TamaActionGate
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.world.core.*
import com.example.llamadroid.tama.world.memory.LivingWorldJournal
import com.example.llamadroid.tama.world.memory.LivingAdventureMilestones
import com.example.llamadroid.tama.world.persistence.WorldInitializer
import com.example.llamadroid.tama.world.persistence.WorldBuildingLayouts
import com.example.llamadroid.tama.world.persistence.WorldStateStore
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptRequest
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptResult
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import com.example.llamadroid.tama.world.presentation.ArcadeReceiptStatus
import com.example.llamadroid.tama.world.presentation.ArcadeSessionLease
import com.example.llamadroid.tama.world.presentation.ArcadeSessionLeaseStatus
import com.example.llamadroid.tama.world.presentation.ArcadeSessionReceipt
import com.example.llamadroid.tama.world.presentation.ArcadeSessionRequest
import com.example.llamadroid.tama.world.presentation.ArcadeSessionRecovery
import com.example.llamadroid.tama.world.presentation.ArcadeSessionRecoveryStatus
import com.example.llamadroid.tama.world.presentation.ArcadeSessionTerminal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** One living world, driven by the existing engine's single clock and mutation lane. */
class TamaWorldController(
    context: Context,
    private val database: TamaDatabase,
    private val farm: FarmRepository,
    engine: TamaGameEngine
) {
    private val appContext = context.applicationContext
    private val store = WorldStateStore(database)
    private val initializer by lazy { WorldInitializer(database, WorldBuildingLayouts.read(context)) }
    private val effects = WorldEffectCommitter(context, database, farm, engine)
    private val parkReceiptStore by lazy {
        TamaParkWorldActionReceiptStore(database.worldActionReceiptDao())
    }
    private val parkReceiptQueue by lazy {
        TamaParkWorldActionQueue(this, database, parkReceiptStore)
    }
    private val journal = LivingWorldJournal(database)
    private val milestones = LivingAdventureMilestones()
    val policies = WorldPolicyRepository(database, this)
    private val _state = MutableStateFlow<WorldState?>(null)
    val state: StateFlow<WorldState?> = _state.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    @Volatile var visible: Boolean = false
        private set
    private var saved: WorldState? = null
    private var pendingEffects = emptyList<WorldEffectRequest>()
    var navigationPolicy: NavigationPolicy? = null
        private set
    private var navigationVersion = "baseline-v1"

    private data class ArcadeTransactionOutcome<T>(
        val value: T,
        val published: WorldState? = null
    )

    /**
     * Runs every Arcade receipt mutation through the same commit boundary.
     * The state flow is published only after Room commits and before deferred
     * delivery; rollback/cancellation reloads both engine and world state.
     */
    private suspend fun <T> runArcadeTransaction(
        block: suspend (com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptDao) -> ArcadeTransactionOutcome<T>
    ): T {
        var outcome: ArcadeTransactionOutcome<T>? = null
        var committed = false
        try {
            TamaCommitEffects.afterCommit {
                outcome = database.withTransaction {
                    block(database.worldActionReceiptDao())
                }
                outcome?.published?.let {
                    saved = it
                    _state.value = it
                    _error.value = null
                }
                committed = true
            }
        } catch (cancelled: CancellationException) {
            if (!committed) {
                withContext(NonCancellable) { recoverAfterWorldTransactionFailure() }
            }
            throw cancelled
        } catch (failure: Exception) {
            if (!committed) {
                withContext(NonCancellable) { recoverAfterWorldTransactionFailure() }
            }
            throw failure
        }
        return checkNotNull(outcome) { "arcade_transaction_uncommitted" }.value
    }

    fun setVisible(value: Boolean) { visible = value }
    internal fun bindEngine(engine: TamaGameEngine) { effects.engine = engine }

    internal suspend fun installNavigation(policy: NavigationPolicy?, version: String) {
        navigationPolicy = policy
        navigationVersion = version
        val current = _state.value ?: return
        if (current.actor.policyVersion == version) return
        flush()
        val latest = _state.value ?: return
        val next = latest.copy(actor = latest.actor.copy(policyVersion = version, navigationMemory = emptyList()))
        store.save(next, saved)
        saved = next
        _state.value = next
    }

    internal suspend fun advance(pet: TamaPet, now: Long) = TamaActionGate.run {
        val current = ensure(pet, now)
        if (_error.value != null) return@run
        val leaseId = TamaArcadeWorldActions.leaseSessionId(current)
        val activeReceipt = database.worldActionReceiptDao().active(pet.id)
            .firstOrNull { it.kind == TamaWorldActionReceiptKind.ARCADE_SESSION }
        if (activeReceipt != null || leaseId != null) {
            val leaseIsCurrent = activeReceipt?.let { row ->
                row.id == leaseId &&
                    TamaArcadeWorldActions.atArcade(current) &&
                    now - row.updatedAt <= TamaArcadeWorldActions.SESSION_TIMEOUT_MS
            } == true
            if (!leaseIsCurrent) {
                try {
                    reconcileArcadeSession(pet.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _error.value = "arcade_recovery_unavailable"
                }
                return@run
            }
        }
        if (now - current.lastSimulatedAt < DEFAULT_TICK_MILLIS) return@run
        val projectedPet = pendingEffects.filterIsInstance<WorldEffectRequest.NeedDelta>().fold(pet) { value, delta ->
            value.copy(stats = value.stats.withWorldNeed(delta.need, delta.delta))
        }
        val projection = projectedPet.worldProjection(current, farm, database.worldDao().relationships(pet.id), current.autonomy)
        val options = WorldSimulationOptions(maxCatchUpTicks = 200, navigationPolicy = navigationPolicy)
        val next = if (now - current.lastSimulatedAt < DEFAULT_TICK_MILLIS * 3) {
            WorldSimulation.step(current, canonicalPetSnapshot = projection,
                now = current.lastSimulatedAt + DEFAULT_TICK_MILLIS, options = options)
        } else WorldSimulation.catchUp(current, projection, now, options)
        accept(current, next, pet, force = !visible)
    }

    suspend fun command(command: WorldCommand): SimulationResult = TamaActionGate.run {
        flush()
        val pet = database.tamaDao().getActivePet()?.let(PetMapper::toDomain) ?: error("pet_missing")
        val current = ensure(pet, System.currentTimeMillis())
        if (!WorldCommerceEffects.allowsCommand(appContext, current, command)) {
            return@run SimulationResult(current, acceptedCommand = false, rejectionReason = "action_unavailable")
        }
        val leaseId = TamaArcadeWorldActions.leaseSessionId(current)
        val activeReceipt = database.worldActionReceiptDao().active(pet.id)
            .firstOrNull { it.kind == TamaWorldActionReceiptKind.ARCADE_SESSION }
        if (activeReceipt != null || leaseId != null) {
            if (activeReceipt?.id != leaseId) {
                try {
                    reconcileArcadeSession(pet.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _error.value = "arcade_recovery_unavailable"
                }
            }
            return@run SimulationResult(
                state = current,
                acceptedCommand = false,
                rejectionReason = "arcade_session_active"
            )
        }
        val projection = pet.worldProjection(current, farm, database.worldDao().relationships(pet.id), current.autonomy,
            (command as? WorldCommand.PerformAction)?.arguments?.get("canonicalAction"))
        val next = WorldSimulation.step(current.copy(actor = current.actor.copy(pendingActivity = null,
            actionArguments = current.actor.actionArguments - FAILURE_KEY)), command, projection,
            now = maxOf(System.currentTimeMillis(), current.lastSimulatedAt + DEFAULT_TICK_MILLIS),
            options = WorldSimulationOptions(navigationPolicy = navigationPolicy))
        accept(current, next, pet, force = true)
    }

    suspend fun setAutonomy(policy: AutonomyPolicy) = TamaActionGate.run {
        flush()
        val current = _state.value ?: return@run
        val next = current.copy(autonomy = policy)
        store.save(next, saved)
        saved = next
        _state.value = next
    }

    /** Explicit recovery after a failed transaction; no reward is repeated before the user retries. */
    suspend fun retry(): SimulationResult = TamaActionGate.run {
        val pet = database.tamaDao().getActivePet()?.let(PetMapper::toDomain) ?: error("pet_missing")
        val current = _state.value ?: ensure(pet, System.currentTimeMillis())
        val arcadeLeaseActive = TamaArcadeWorldActions.leaseSessionId(current) != null
        val clean = current.copy(actor = current.actor.copy(
            actionArguments = current.actor.actionArguments - FAILURE_KEY,
            action = if (arcadeLeaseActive) ActionId.USE_ARCADE else current.actor.action,
            actionState = if (arcadeLeaseActive || current.actor.actionTicksRemaining > 0) {
                ActionState.RUNNING
            } else ActionState.IDLE))
        _error.value = null
        _state.value = clean
        val projection = pet.worldProjection(clean, farm, database.worldDao().relationships(pet.id), clean.autonomy)
        accept(clean, WorldSimulation.step(clean, canonicalPetSnapshot = projection,
            now = maxOf(System.currentTimeMillis(), clean.lastSimulatedAt + DEFAULT_TICK_MILLIS),
            options = WorldSimulationOptions(navigationPolicy = navigationPolicy)), pet, force = true)
    }

    /** UI recovery must remain usable even when Room or initialization is still unavailable. */
    suspend fun retryFromUi() {
        try {
            retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportFailure()
        }
    }

    suspend fun queueActivity(intent: PendingActivityIntent): SimulationResult = TamaActionGate.run {
        val result = command(if (intent.destinationId == LegacyLocationAliases.HOME) WorldCommand.ReturnHome
            else WorldCommand.GoToStructure(intent.destinationId))
        if (!result.acceptedCommand) return@run result
        val next = result.state.copy(actor = result.state.actor.copy(pendingActivity = intent))
        store.save(next, saved)
        saved = next
        _state.value = next
        result.copy(state = next)
    }

    suspend fun queueAction(action: ActionId, targetId: String?, arguments: Map<String, String>,
                            destinationId: String? = null): SimulationResult = TamaActionGate.run {
        val pet = database.tamaDao().getActivePet()?.let(PetMapper::toDomain) ?: error("pet_missing")
        val current = ensure(pet, System.currentTimeMillis())
        // Park receipts target a live world NPC. Route through PerformAction so
        // the core approaches the NPC and stays in WORLD; GoToStructure would
        // enter the Park interior and make the adjacency completion impossible.
        val routesToLiveWorldTarget = arguments["canonicalAction"] == TamaParkWorldAction.CANONICAL_ACTION
        if (destinationId != null && !routesToLiveWorldTarget &&
            (current.actor.presence == PresenceMode.WORLD || current.actor.structureId != destinationId)) {
            return@run queueActivity(PendingActivityIntent("WORLD_ACTION", destinationId,
                arguments + mapOf("actionId" to action.name, "targetId" to targetId.orEmpty())))
        }
        command(WorldCommand.PerformAction(action, targetId,
            targetX = if (action == ActionId.USE) current.actor.x else null,
            targetY = if (action == ActionId.USE) current.actor.y else null, arguments = arguments))
    }

    /** Admit a Park request and its physical route as one durable operation. */
    internal suspend fun queueParkAction(
        request: TamaWorldActionReceiptRequest
    ): TamaParkWorldActionQueue.Result = parkReceiptQueue.queue(request)

    /**
     * Room may roll back an outer receipt-admission transaction after a
     * nested command has published a candidate state. Reload every canonical
     * snapshot before another clock/action can flush that stale candidate.
     */
    internal suspend fun recoverAfterParkQueueFailure() {
        recoverAfterWorldTransactionFailure()
    }

    /**
     * Room can roll back after the engine has published its in-memory pet.
     * Reload the canonical pet and world snapshot before another clock tick
     * can flush that stale candidate back into storage.
     */
    internal suspend fun recoverAfterWorldTransactionFailure() {
        invalidate()
        runCatching { effects.engine.reloadPersistedPet() }
        val pet = runCatching {
            database.tamaDao().getActivePet()?.let(PetMapper::toDomain)
        }.getOrNull() ?: return
        runCatching { ensure(pet, System.currentTimeMillis()) }
    }

    /** Terminal Park results survive process death for the result dialog. */
    internal fun observeParkActionReceipts(
        petId: String
    ): Flow<List<TamaWorldActionReceiptEntity>> =
        database.worldActionReceiptDao().observeUnacknowledged(petId)

    internal suspend fun acknowledgeParkAction(
        petId: String,
        receiptId: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = TamaActionGate.run {
        database.worldActionReceiptDao().acknowledge(petId, receiptId, now) == 1
    }

    /** Creates the durable Arcade lease before the local game starts. */
    suspend fun beginArcadeSession(request: ArcadeSessionRequest): ArcadeSessionLease =
        TamaActionGate.run {
            TamaArcadeWorldActions.validate(request)?.let {
                return@run TamaArcadeWorldActions.lease(request, ArcadeSessionLeaseStatus.UNAVAILABLE)
            }
            val now = System.currentTimeMillis()
            val pet = database.tamaDao().getActivePet()?.let(PetMapper::toDomain)
                ?.takeIf { it.id == request.petId }
                ?: return@run TamaArcadeWorldActions.lease(request, ArcadeSessionLeaseStatus.UNAVAILABLE)
            val current = ensure(pet, now)
            if (_error.value != null || !TamaArcadeWorldActions.atArcade(current)) {
                return@run TamaArcadeWorldActions.lease(request, ArcadeSessionLeaseStatus.UNAVAILABLE)
            }
            runArcadeTransaction transaction@{ dao ->
                val existing = dao.byId(request.petId, request.sessionId)
                if (existing != null) {
                    val stored = TamaArcadeWorldActions.decodeRequest(existing.requestJson)
                    val storedSession = stored?.let(TamaArcadeWorldActions::requestFromReceipt)
                    if (stored == null || storedSession == null ||
                        TamaArcadeWorldActions.validate(storedSession) != null ||
                        !TamaArcadeWorldActions.sameSession(stored, request) ||
                        !TamaArcadeWorldActions.belongsToWorld(stored, current.worldId) ||
                        existing.worldId != current.worldId
                    ) {
                        return@transaction ArcadeTransactionOutcome(
                            TamaArcadeWorldActions.lease(request, ArcadeSessionLeaseStatus.UNAVAILABLE)
                        )
                    }
                    return@transaction when {
                        existing.status in TamaWorldActionReceiptStatus.terminal -> {
                            ArcadeTransactionOutcome(
                                TamaArcadeWorldActions.lease(request, ArcadeSessionLeaseStatus.TERMINAL)
                            )
                        }
                        existing.status in TamaWorldActionReceiptStatus.active -> {
                            if (now - existing.updatedAt > TamaArcadeWorldActions.SESSION_TIMEOUT_MS) {
                                TamaArcadeWorldActions.rejectActive(dao, existing, "arcade_session_expired", now)
                                val next = TamaArcadeWorldActions.clearLease(current, request.sessionId)
                                store.save(next, saved)
                                ArcadeTransactionOutcome(
                                    TamaArcadeWorldActions.lease(request, ArcadeSessionLeaseStatus.TERMINAL), next
                                )
                            } else {
                                if (existing.status == TamaWorldActionReceiptStatus.QUEUED) {
                                    dao.markRunning(existing.petId, existing.id, now)
                                }
                                val next = TamaArcadeWorldActions.withLease(current, request.sessionId)
                                store.save(next, saved)
                                ArcadeTransactionOutcome(
                                    TamaArcadeWorldActions.lease(request, ArcadeSessionLeaseStatus.RUNNING), next
                                )
                            }
                        }
                        else -> ArcadeTransactionOutcome(
                            TamaArcadeWorldActions.lease(request, ArcadeSessionLeaseStatus.UNAVAILABLE)
                        )
                    }
                }
                val otherActive = dao.active(request.petId).firstOrNull {
                    it.kind == TamaWorldActionReceiptKind.ARCADE_SESSION
                }
                if (otherActive != null) {
                    return@transaction ArcadeTransactionOutcome(
                        TamaArcadeWorldActions.lease(request, ArcadeSessionLeaseStatus.UNAVAILABLE)
                    )
                }
                val receiptRequest = TamaArcadeWorldActions.receiptRequest(request, current.worldId, now)
                val candidate = TamaWorldActionReceiptEntity(
                    petId = request.petId,
                    id = request.sessionId,
                    worldId = current.worldId,
                    kind = TamaWorldActionReceiptKind.ARCADE_SESSION,
                    requestJson = TamaArcadeWorldActions.encodeRequest(receiptRequest),
                    status = TamaWorldActionReceiptStatus.RUNNING,
                    createdAt = now,
                    updatedAt = now
                )
                val (_, admitted) = dao.createOrGet(candidate)
                val admittedRequest = TamaArcadeWorldActions.decodeRequest(admitted.requestJson)
                val admittedSession = admittedRequest?.let(TamaArcadeWorldActions::requestFromReceipt)
                if (admittedRequest == null || admittedSession == null ||
                    TamaArcadeWorldActions.validate(admittedSession) != null ||
                    !TamaArcadeWorldActions.sameSession(admittedRequest, request) ||
                    !TamaArcadeWorldActions.belongsToWorld(admittedRequest, current.worldId) ||
                    admitted.status !in TamaWorldActionReceiptStatus.active
                ) {
                    return@transaction ArcadeTransactionOutcome(
                        TamaArcadeWorldActions.lease(request, ArcadeSessionLeaseStatus.UNAVAILABLE)
                    )
                }
                val next = TamaArcadeWorldActions.withLease(current, request.sessionId)
                store.save(next, saved)
                ArcadeTransactionOutcome(
                    TamaArcadeWorldActions.lease(request, ArcadeSessionLeaseStatus.RUNNING), next
                )
            }
        }

    /** Completes one active receipt and applies its bounded reward atomically. */
    suspend fun submitArcadeSession(request: ArcadeSessionRequest): ArcadeSessionReceipt =
        TamaActionGate.run {
            TamaArcadeWorldActions.validate(request)?.let {
                return@run TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
            }
            val dao = database.worldActionReceiptDao()
            val row = dao.byId(request.petId, request.sessionId)
                ?: return@run TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
            val stored = TamaArcadeWorldActions.decodeRequest(row.requestJson)
                ?: return@run TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
            val storedSession = TamaArcadeWorldActions.requestFromReceipt(stored)
            if (storedSession == null || stored.receiptId != row.id || stored.petId != row.petId ||
                stored.kind != row.kind || TamaArcadeWorldActions.validate(storedSession) != null ||
                !TamaArcadeWorldActions.sameSession(stored, request)
            ) {
                return@run TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
            }
            if (row.status in TamaWorldActionReceiptStatus.terminal) {
                return@run TamaArcadeWorldActions.terminalReceipt(request, row)
            }
            val pet = database.tamaDao().getActivePet()?.let(PetMapper::toDomain)
                ?.takeIf { it.id == request.petId }
                ?: return@run TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
            val now = System.currentTimeMillis()
            val current = ensure(pet, now)
            runArcadeTransaction transaction@{ latestDao ->
                val latest = latestDao.byId(request.petId, request.sessionId)
                    ?: return@transaction ArcadeTransactionOutcome(
                        TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
                    )
                if (latest.status in TamaWorldActionReceiptStatus.terminal) {
                    return@transaction ArcadeTransactionOutcome(
                        TamaArcadeWorldActions.terminalReceipt(request, latest)
                    )
                }
                if (latest.status !in TamaWorldActionReceiptStatus.active) {
                    return@transaction ArcadeTransactionOutcome(
                        TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
                    )
                }
                if (now - latest.updatedAt > TamaArcadeWorldActions.SESSION_TIMEOUT_MS) {
                    TamaArcadeWorldActions.rejectActive(latestDao, latest, "arcade_session_expired", now)
                    val next = TamaArcadeWorldActions.clearLease(current, request.sessionId)
                    store.save(next, saved)
                    return@transaction ArcadeTransactionOutcome(
                        TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED), next
                    )
                }
                val latestRequest = TamaArcadeWorldActions.decodeRequest(latest.requestJson)
                val latestSession = latestRequest?.let(TamaArcadeWorldActions::requestFromReceipt)
                if (latestRequest == null || latestSession == null ||
                    latestRequest.receiptId != latest.id || latestRequest.petId != latest.petId ||
                    latestRequest.kind != latest.kind ||
                    TamaArcadeWorldActions.validate(latestSession) != null ||
                    !TamaArcadeWorldActions.sameSession(latestRequest, request) ||
                    !TamaArcadeWorldActions.belongsToWorld(latestRequest, current.worldId) ||
                    latest.worldId != current.worldId ||
                    !TamaArcadeWorldActions.atArcade(current) ||
                    TamaArcadeWorldActions.leaseSessionId(current) != request.sessionId
                ) {
                    TamaArcadeWorldActions.rejectActive(latestDao, latest, "arcade_lease_missing", now)
                    val next = TamaArcadeWorldActions.clearLease(current, request.sessionId)
                    store.save(next, saved)
                    return@transaction ArcadeTransactionOutcome(
                        TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED), next
                    )
                }
                if (latest.status == TamaWorldActionReceiptStatus.QUEUED) {
                    check(latestDao.markRunning(latest.petId, latest.id, now) == 1) {
                        "arcade_receipt_claim_failed"
                    }
                }
                val reward = TamaArcadeWorldActions.rewardFor(request)
                effects.engine.reloadPersistedPet()
                check(effects.engine.applyArcadeRewardLocked(reward.coins, reward.happiness)) {
                    "arcade_reward_unavailable"
                }
                val terminalRequest = TamaArcadeWorldActions.receiptRequest(
                    request,
                    latest.worldId,
                    now,
                    requestedAt = latestRequest.requestedAt
                )
                check(latestDao.updateActiveRequest(
                    petId = latest.petId,
                    id = latest.id,
                    requestJson = TamaArcadeWorldActions.encodeRequest(terminalRequest),
                    updatedAt = now
                ) == 1) { "arcade_receipt_claim_failed" }
                val result = TamaWorldActionReceiptResult(
                    success = true,
                    action = TamaWorldActionReceiptKind.ARCADE_SESSION,
                    completedAt = now,
                    rewardCoins = reward.coins,
                    rewardHappiness = reward.happiness
                )
                check(latestDao.completeActive(
                    petId = latest.petId,
                    id = latest.id,
                    status = TamaWorldActionReceiptStatus.SUCCEEDED,
                    updatedAt = now,
                    completedAt = now,
                    resultJson = TamaArcadeWorldActions.encodeResult(result)
                ) == 1) { "arcade_receipt_claim_failed" }
                val updatedPet = database.tamaDao().getPet(request.petId)?.let(PetMapper::toDomain)
                val released = TamaArcadeWorldActions.clearLease(current, request.sessionId)
                val next = released.copy(
                    actor = released.actor.copy(
                        needs = updatedPet?.stats?.worldNeeds() ?: current.actor.needs
                    )
                )
                store.save(next, saved)
                ArcadeTransactionOutcome(
                    TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.SUCCEEDED, result), next
                )
            }
        }

    /** Releases an active session without awarding anything. */
    suspend fun cancelArcadeSession(request: ArcadeSessionRequest): ArcadeSessionReceipt =
        TamaActionGate.run {
            if (request.petId.isBlank() || request.sessionId.isBlank() || request.gameId.isBlank()) {
                return@run TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
            }
            val dao = database.worldActionReceiptDao()
            val row = dao.byId(request.petId, request.sessionId)
                ?: return@run TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
            val stored = TamaArcadeWorldActions.decodeRequest(row.requestJson)
            if (stored == null || !TamaArcadeWorldActions.sameSession(stored, request)) {
                return@run TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
            }
            if (row.status in TamaWorldActionReceiptStatus.terminal) {
                return@run TamaArcadeWorldActions.terminalReceipt(request, row)
            }
            val pet = database.tamaDao().getActivePet()?.let(PetMapper::toDomain)
                ?.takeIf { it.id == request.petId }
                ?: return@run TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
            val current = ensure(pet, System.currentTimeMillis())
            val now = System.currentTimeMillis()
            runArcadeTransaction transaction@{ latestDao ->
                val latest = latestDao.byId(request.petId, request.sessionId)
                    ?: return@transaction ArcadeTransactionOutcome(
                        TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
                    )
                if (latest.status in TamaWorldActionReceiptStatus.terminal) {
                    return@transaction ArcadeTransactionOutcome(
                        TamaArcadeWorldActions.terminalReceipt(request, latest)
                    )
                }
                if (latest.status !in TamaWorldActionReceiptStatus.active) {
                    return@transaction ArcadeTransactionOutcome(
                        TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED)
                    )
                }
                TamaArcadeWorldActions.rejectActive(latestDao, latest, "arcade_session_cancelled", now)
                val next = TamaArcadeWorldActions.clearLease(current, request.sessionId)
                store.save(next, saved)
                ArcadeTransactionOutcome(
                    TamaArcadeWorldActions.receipt(request, ArcadeReceiptStatus.REJECTED), next
                )
            }
        }

    /** Recovers one active persisted lease after process death. */
    suspend fun reconcileArcadeSession(petId: String): ArcadeSessionLease? =
        TamaActionGate.run {
            val pet = database.tamaDao().getActivePet()?.let(PetMapper::toDomain)
                ?.takeIf { it.id == petId } ?: return@run null
            val now = System.currentTimeMillis()
            val current = ensure(pet, now)
            runArcadeTransaction transaction@{ dao ->
                val rows = dao.active(petId).filter { it.kind == TamaWorldActionReceiptKind.ARCADE_SESSION }
                val row = rows.firstOrNull()
                rows.drop(1).forEach { TamaArcadeWorldActions.rejectActive(dao, it, "arcade_duplicate_session", now) }
                if (row == null) {
                    val next = TamaArcadeWorldActions.clearLease(current)
                    if (next == current) {
                        return@transaction ArcadeTransactionOutcome<ArcadeSessionLease?>(null)
                    }
                    store.save(next, saved)
                    return@transaction ArcadeTransactionOutcome<ArcadeSessionLease?>(null, next)
                }
                val stored = TamaArcadeWorldActions.decodeRequest(row.requestJson)
                val session = stored?.let(TamaArcadeWorldActions::requestFromReceipt)
                if (stored == null || session == null || stored.receiptId != row.id ||
                    stored.petId != row.petId || stored.kind != row.kind ||
                    TamaArcadeWorldActions.validate(session) != null ||
                    !TamaArcadeWorldActions.sameSession(stored, session) ||
                    !TamaArcadeWorldActions.belongsToWorld(stored, current.worldId) ||
                    row.worldId != current.worldId ||
                    now - row.updatedAt > TamaArcadeWorldActions.SESSION_TIMEOUT_MS ||
                    !TamaArcadeWorldActions.atArcade(current)
                ) {
                    TamaArcadeWorldActions.rejectActive(dao, row, "arcade_recovery_invalid", now)
                    val next = TamaArcadeWorldActions.clearLease(current, session?.sessionId ?: row.id)
                    store.save(next, saved)
                    return@transaction ArcadeTransactionOutcome<ArcadeSessionLease?>(null, next)
                }
                if (row.status == TamaWorldActionReceiptStatus.QUEUED) dao.markRunning(row.petId, row.id, now)
                val next = TamaArcadeWorldActions.withLease(current, session.sessionId)
                store.save(next, saved)
                ArcadeTransactionOutcome(
                    TamaArcadeWorldActions.lease(session, ArcadeSessionLeaseStatus.RECONCILE_REQUIRED), next
                )
            }
        }

    /**
     * Rebuilds the Arcade host after process death. Active leases take
     * precedence over terminal rows; a committed terminal success carries the
     * persisted metrics and reward so the UI can safely recreate its summary.
     */
    suspend fun recoverArcadeSession(petId: String): ArcadeSessionRecovery =
        TamaActionGate.run {
            val active = reconcileArcadeSession(petId)
            if (active != null) {
                return@run ArcadeSessionRecovery(
                    status = ArcadeSessionRecoveryStatus.ACTIVE,
                    activeLease = active
                )
            }
            val row = database.worldActionReceiptDao().unacknowledged(petId)
                .firstOrNull { it.kind == TamaWorldActionReceiptKind.ARCADE_SESSION }
                ?: return@run ArcadeSessionRecovery()
            val terminal = TamaArcadeWorldActions.terminalSession(row)
            if (terminal != null) {
                return@run ArcadeSessionRecovery(
                    status = ArcadeSessionRecoveryStatus.TERMINAL,
                    terminal = terminal
                )
            }
            val stored = TamaArcadeWorldActions.decodeRequest(row.requestJson)
            val session = stored?.let(TamaArcadeWorldActions::requestFromReceipt)
            if (stored != null && session != null &&
                TamaArcadeWorldActions.validate(session) == null &&
                TamaArcadeWorldActions.belongsToWorld(stored, row.worldId)
            ) {
                return@run ArcadeSessionRecovery(
                    status = ArcadeSessionRecoveryStatus.TERMINAL,
                    terminal = ArcadeSessionTerminal(
                        session,
                        TamaArcadeWorldActions.receipt(session, ArcadeReceiptStatus.REJECTED)
                    )
                )
            }
            ArcadeSessionRecovery(ArcadeSessionRecoveryStatus.UNAVAILABLE)
        }

    /** A terminal dialog is dismissed only after its durable receipt is acked. */
    suspend fun acknowledgeArcadeSession(request: ArcadeSessionRequest): Boolean =
        TamaActionGate.run {
            if (request.petId.isBlank() || request.sessionId.isBlank()) return@run false
            runArcadeTransaction transaction@{ dao ->
                val row = dao.byId(request.petId, request.sessionId)
                    ?: return@transaction ArcadeTransactionOutcome(false)
                val stored = TamaArcadeWorldActions.decodeRequest(row.requestJson)
                if (stored == null || stored.receiptId != row.id || stored.petId != row.petId ||
                    stored.kind != row.kind || !TamaArcadeWorldActions.sameSession(stored, request)
                ) {
                    return@transaction ArcadeTransactionOutcome(false)
                }
                if (row.status == TamaWorldActionReceiptStatus.ACKNOWLEDGED) {
                    return@transaction ArcadeTransactionOutcome(true)
                }
                if (row.status !in setOf(
                        TamaWorldActionReceiptStatus.SUCCEEDED,
                        TamaWorldActionReceiptStatus.REJECTED,
                        TamaWorldActionReceiptStatus.FAILED
                    )
                ) {
                    return@transaction ArcadeTransactionOutcome(false)
                }
                ArcadeTransactionOutcome(
                    dao.acknowledge(request.petId, request.sessionId, System.currentTimeMillis()) == 1
                )
            }
        }

    internal suspend fun recordActivityEvent(event: com.example.llamadroid.tama.data.TamaEvent) {
        val importance = when (event.eventType) {
            com.example.llamadroid.tama.data.EventType.QUEST_COMPLETED,
            com.example.llamadroid.tama.data.EventType.BATTLE_WON,
            com.example.llamadroid.tama.data.EventType.GRADUATED,
            com.example.llamadroid.tama.data.EventType.MADE_FRIEND,
            com.example.llamadroid.tama.data.EventType.MARRIED,
            com.example.llamadroid.tama.data.EventType.HAD_CHILD -> EventImportance.MAJOR
            com.example.llamadroid.tama.data.EventType.EVOLVED,
            com.example.llamadroid.tama.data.EventType.LEVEL_UP,
            com.example.llamadroid.tama.data.EventType.BATTLE_LOST -> EventImportance.MEMORABLE
            com.example.llamadroid.tama.data.EventType.HARVESTED,
            com.example.llamadroid.tama.data.EventType.FOUND_ITEM,
            com.example.llamadroid.tama.data.EventType.MET_NPC,
            com.example.llamadroid.tama.data.EventType.RECEIVED_GIFT -> EventImportance.NOTABLE
            else -> return
        }
        val pet = database.tamaDao().getPet(event.petId)?.let(PetMapper::toDomain) ?: return
        val current = ensure(pet, event.timestamp)
        journal.record(current, current.copy(lastSimulatedAt = event.timestamp), listOf(WorldEffectRequest.Event(
            event.eventType.name, importance, event.petId,
            mapOf("details" to event.details, "legacyEventId" to event.id) +
                listOfNotNull(event.locationId?.let { "locationId" to it }, event.npcId?.let { "npcId" to it }).toMap()
        )), transitionId = "activity:${event.id}")
    }

    private suspend fun ensure(pet: TamaPet, now: Long): WorldState {
        _state.value?.takeIf { it.petId == pet.id }?.let { return it }
        farm.ensureUnlockedFarmTiles(pet.id)
        var loaded = initializer.ensure(pet, now).let { it.copy(actor = it.actor.copy(needs = pet.stats.worldNeeds())) }
        policies.loadIntoWorld(pet.id)
        database.withTransaction {
            TamaParkWorldActionReceiptRecovery.rejectOrphans(loaded, parkReceiptStore, now)
            val restored = TamaArcadeWorldActions.restoreLease(
                loaded, database.worldActionReceiptDao(), now
            )
            if (restored != loaded) {
                store.save(restored, loaded)
                loaded = restored
            }
        }
        if (loaded.actor.policyVersion != navigationVersion) {
            loaded = loaded.copy(actor = loaded.actor.copy(policyVersion = navigationVersion, navigationMemory = emptyList()))
        }
        saved = loaded
        pendingEffects = emptyList()
        _state.value = loaded
        _error.value = loaded.actor.actionArguments[FAILURE_KEY]
        return loaded
    }

    private suspend fun accept(before: WorldState, result: SimulationResult, pet: TamaPet, force: Boolean): SimulationResult {
        if (!result.acceptedCommand) return result
        val allEffects = WorldFoodEffects.normalize(pendingEffects + result.effects + WorldNpcEncounters.effects(before, result.state))
        val important = allEffects.any { it !is WorldEffectRequest.NeedDelta } ||
            before.actor.presence != result.state.actor.presence || before.actor.actionState != result.state.actor.actionState
        val shouldSave = force || important || result.state.lastSimulatedAt - (saved?.lastSimulatedAt ?: 0) >= 1_000L
        return try {
            var next = result.state
            if (shouldSave) {
                TamaCommitEffects.afterCommit {
                    database.withTransaction {
                        val latest = database.tamaDao().getPet(pet.id)?.let(PetMapper::toDomain) ?: error("pet_missing")
                        val relationshipsBefore = database.worldDao().relationships(pet.id)
                        TamaParkWorldActionReceiptRecovery.rejectReplaced(
                            before, next, allEffects, parkReceiptStore, next.lastSimulatedAt
                        )
                        val pending = next.actor.pendingActivity?.takeIf {
                            it.action != TamaArcadeWorldActions.LEASE_ACTION &&
                                next.actor.presence != PresenceMode.WORLD &&
                                next.actor.structureId == it.destinationId
                        }
                        if (pending != null) next = next.copy(actor = next.actor.copy(pendingActivity = null))
                        var updatedPet = effects.commit(latest, next, allEffects)
                        if (pending != null) {
                            if (pending.action == "WORLD_ACTION") {
                                val started = WorldSimulation.step(next, WorldCommand.PerformAction(
                                    ActionId.valueOf(pending.arguments.getValue("actionId")),
                                    pending.arguments["targetId"]?.takeIf { it.isNotEmpty() },
                                    arguments = pending.arguments - setOf("actionId", "targetId")),
                                    updatedPet.worldProjection(next, farm, database.worldDao().relationships(pet.id), next.autonomy, pending.arguments["canonicalAction"]),
                                    next.lastSimulatedAt + DEFAULT_TICK_MILLIS,
                                    WorldSimulationOptions(navigationPolicy = navigationPolicy, simulateNpcs = false))
                                check(started.acceptedCommand) { started.rejectionReason ?: "world_action_failed" }
                                next = started.state
                                updatedPet = effects.commit(updatedPet, next, started.effects)
                            } else effects.resumeActivity(pending)
                            updatedPet = database.tamaDao().getPet(pet.id)?.let(PetMapper::toDomain) ?: updatedPet
                        }
                        val canonicalRelationships = database.worldDao().relationships(pet.id).associateBy { it.npcId }
                        next = next.copy(
                            actor = next.actor.copy(needs = updatedPet.stats.worldNeeds()),
                            npcs = next.npcs.map { npc ->
                                val relationship = canonicalRelationships[npc.id] ?: return@map npc
                                npc.copy(relationships = npc.relationships + (pet.id to RelationshipProjection(
                                    relationship.familiarity.toInt(), relationship.friendship.toInt(), relationship.trust.toInt()
                                )))
                            }
                        )
                        store.save(next, saved)
                        val milestoneEvents = milestones.events(saved ?: before, next, relationshipsBefore,
                            database.worldDao().relationships(pet.id))
                        journal.record(saved ?: before, next, allEffects.filterIsInstance<WorldEffectRequest.Event>() + milestoneEvents)
                        if (allEffects.any { it is WorldEffectRequest.FarmTransition || it is WorldEffectRequest.CanonicalAction }) {
                            TamaCommitEffects.deferOrRun("notifications:${pet.id}") {
                                TamaNotificationScheduler.scheduleForPet(appContext, pet.id)
                            }
                        }
                    }
                    // Publish committed state before cancellable external delivery. Closing the
                    // engine during a notification must never flush an older snapshot over it.
                    saved = next
                    pendingEffects = emptyList()
                    _state.value = next
                    _error.value = null
                }
            } else pendingEffects = allEffects
            _state.value = next
            _error.value = null
            result.copy(state = next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            try {
                effects.engine.reloadPersistedPet()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Storage failures must still leave the saved world action recoverable below.
            }
            // Transaction rollback preserves rewards and objects together; retain a recoverable action.
            pendingEffects = emptyList()
            _error.value = failure.message ?: "world_action_failed"
            val rolledBack = saved ?: before
            val failedActor = result.state.actor
            val interrupted = before.actor.takeIf { it.action == failedActor.action } ?: failedActor
            val pending = failedActor.pendingCommand ?: before.actor.pendingCommand ?: failedActor.action
                .takeUnless { it in setOf(ActionId.WAIT, ActionId.WALK, ActionId.RUN) }
                ?.let { PendingWorldCommand(it, failedActor.actionTargetId, failedActor.actionTargetX,
                    failedActor.actionTargetY, (interrupted.actionArguments + failedActor.actionArguments) - FAILURE_KEY) }
            val recoverable = rolledBack.copy(actor = rolledBack.actor.copy(actionState = ActionState.BLOCKED,
                actionTicksRemaining = 0, pendingCommand = pending,
                pendingActivity = failedActor.pendingActivity ?: before.actor.pendingActivity,
                actionArguments = rolledBack.actor.actionArguments + (FAILURE_KEY to _error.value!!)))
            _state.value = recoverable
            try {
                store.save(recoverable, saved)
                saved = recoverable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Storage itself may be unavailable; keep the recoverable state visible in memory.
            }
            SimulationResult(_state.value!!, acceptedCommand = false, rejectionReason = _error.value)
        }
    }

    suspend fun flush() = TamaActionGate.run {
        val current = _state.value ?: return@run
        if (_error.value != null) return@run
        val pet = database.tamaDao().getPet(current.petId)?.let(PetMapper::toDomain) ?: return@run
        accept(saved ?: current, SimulationResult(current), pet, force = true)
        Unit
    }

    /** Called after import, reset or adoption; no stale actor can overwrite restored progress. */
    fun invalidate() {
        _state.value = null
        saved = null
        pendingEffects = emptyList()
        _error.value = null
    }

    internal fun reportFailure() { _error.value = "world_unavailable" }

    private companion object { const val FAILURE_KEY = "_worldFailure" }
}
