package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.data.LegacyLocationAliases
import com.example.llamadroid.tama.db.TamaDatabase
// TG re-points these to com.example.llamadroid.tama.db.TamaActionReceipt* and actionReceiptDao() (wave 2).
import com.example.llamadroid.tama.db.TamaActionReceiptDao
import com.example.llamadroid.tama.db.TamaActionReceiptEntity
import com.example.llamadroid.tama.db.TamaActionReceiptKind
import com.example.llamadroid.tama.db.TamaActionReceiptResult
import com.example.llamadroid.tama.db.TamaActionReceiptStatus

/**
 * Durable Arcade sessions for the classic pet. The lease is the active
 * receipt row: begin admits it before the local game starts and submit pays
 * only while completing that same row, inside the same Room transaction as the
 * reward (exactly once). Every operation runs through
 * [TamaGameEngine.runClassicTransaction], which owns the mutation gate and
 * reloads the pet after a rollback.
 */
class TamaArcadeSessions(
    private val engine: TamaGameEngine,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private fun receiptDao(db: TamaDatabase): TamaActionReceiptDao =
        db.actionReceiptDao()

    private suspend fun <T> transaction(block: suspend (TamaActionReceiptDao) -> T): T =
        engine.runClassicTransaction { db, _ -> block(receiptDao(db)) }

    private fun activePet(petId: String) = engine.pet.value?.takeIf { it.id == petId }

    private fun atArcade(): Boolean =
        LegacyLocationAliases.normalize(engine.pet.value?.currentLocationId) == LegacyLocationAliases.ARCADE

    /** Adapter handed to the Arcade screen. */
    fun bridge(): ArcadeSessionBridge = ArcadeSessionBridge.from(
        beginSession = ::beginArcadeSession,
        submitSession = ::submitArcadeSession,
        cancelSession = ::cancelArcadeSession,
        reconcileSession = ::reconcileArcadeSession,
        recoverSession = ::recoverArcadeSession,
        acknowledgeSession = ::acknowledgeArcadeSession
    )

    /** Creates the durable Arcade lease before the local game starts. */
    suspend fun beginArcadeSession(request: ArcadeSessionRequest): ArcadeSessionLease {
        val rules = TamaArcadeSessionRules
        if (rules.validate(request) != null) return rules.lease(request, ArcadeSessionLeaseStatus.UNAVAILABLE)
        val unavailable = rules.lease(request, ArcadeSessionLeaseStatus.UNAVAILABLE)
        val known = transaction { it.byId(request.petId, request.sessionId) } != null
        // A replayed session must not move (or charge) the pet again.
        if (!known && !engine.travelToId(LegacyLocationAliases.ARCADE).success) return unavailable
        return transaction { dao ->
            val now = clock()
            activePet(request.petId) ?: return@transaction unavailable
            if (!atArcade()) return@transaction unavailable
            val existing = dao.byId(request.petId, request.sessionId)
            if (existing != null) {
                val stored = rules.decodeRequest(existing.requestJson)
                val storedSession = stored?.let(rules::requestFromReceipt)
                if (stored == null || storedSession == null || rules.validate(storedSession) != null ||
                    !rules.sameSession(stored, request)
                ) return@transaction unavailable
                return@transaction when {
                    existing.status in TamaActionReceiptStatus.terminal ->
                        rules.lease(request, ArcadeSessionLeaseStatus.TERMINAL)
                    existing.status in TamaActionReceiptStatus.active -> {
                        if (now - existing.updatedAt > TamaArcadeSessionRules.SESSION_TIMEOUT_MS) {
                            rules.rejectActive(dao, existing, "arcade_session_expired", now)
                            rules.lease(request, ArcadeSessionLeaseStatus.TERMINAL)
                        } else {
                            if (existing.status == TamaActionReceiptStatus.QUEUED) {
                                dao.markRunning(existing.petId, existing.id, now)
                            }
                            rules.lease(request, ArcadeSessionLeaseStatus.RUNNING)
                        }
                    }
                    else -> unavailable
                }
            }
            val otherActive = dao.active(request.petId).firstOrNull {
                it.kind == TamaActionReceiptKind.ARCADE_SESSION
            }
            if (otherActive != null) return@transaction unavailable
            val candidate = TamaActionReceiptEntity(
                petId = request.petId,
                id = request.sessionId,
                worldId = TamaArcadeSessionRules.CLASSIC_RECEIPT_WORLD_ID,
                kind = TamaActionReceiptKind.ARCADE_SESSION,
                requestJson = rules.encodeRequest(rules.receiptRequest(request, now)),
                status = TamaActionReceiptStatus.RUNNING,
                createdAt = now,
                updatedAt = now
            )
            val (_, admitted) = dao.createOrGet(candidate)
            val admittedRequest = rules.decodeRequest(admitted.requestJson)
            val admittedSession = admittedRequest?.let(rules::requestFromReceipt)
            if (admittedRequest == null || admittedSession == null ||
                rules.validate(admittedSession) != null ||
                !rules.sameSession(admittedRequest, request) ||
                admitted.status !in TamaActionReceiptStatus.active
            ) return@transaction unavailable
            rules.lease(request, ArcadeSessionLeaseStatus.RUNNING)
        }
    }

    /** Completes one active receipt and applies its bounded reward atomically. */
    suspend fun submitArcadeSession(request: ArcadeSessionRequest): ArcadeSessionReceipt {
        val rules = TamaArcadeSessionRules
        fun rejected() = rules.receipt(request, ArcadeReceiptStatus.REJECTED)
        if (rules.validate(request) != null) return rejected()
        return transaction { dao ->
            val now = clock()
            val row = dao.byId(request.petId, request.sessionId) ?: return@transaction rejected()
            val stored = rules.decodeRequest(row.requestJson) ?: return@transaction rejected()
            val storedSession = rules.requestFromReceipt(stored)
            if (storedSession == null || stored.receiptId != row.id || stored.petId != row.petId ||
                stored.kind != row.kind || rules.validate(storedSession) != null ||
                !rules.sameSession(stored, request)
            ) return@transaction rejected()
            if (row.status in TamaActionReceiptStatus.terminal) {
                return@transaction rules.terminalReceipt(request, row)
            }
            if (row.status !in TamaActionReceiptStatus.active) return@transaction rejected()
            activePet(request.petId) ?: return@transaction rejected()
            if (now - row.updatedAt > TamaArcadeSessionRules.SESSION_TIMEOUT_MS) {
                rules.rejectActive(dao, row, "arcade_session_expired", now)
                return@transaction rejected()
            }
            if (!atArcade()) {
                rules.rejectActive(dao, row, "arcade_lease_missing", now)
                return@transaction rejected()
            }
            if (row.status == TamaActionReceiptStatus.QUEUED) {
                check(dao.markRunning(row.petId, row.id, now) == 1) { "arcade_receipt_claim_failed" }
            }
            val reward = rules.rewardFor(request)
            check(engine.applyArcadeRewardLocked(reward.coins, reward.happiness)) {
                "arcade_reward_unavailable"
            }
            val terminalRequest = rules.receiptRequest(
                request, now, worldId = row.worldId.ifBlank { TamaArcadeSessionRules.CLASSIC_RECEIPT_WORLD_ID },
                requestedAt = stored.requestedAt
            )
            check(dao.updateActiveRequest(
                petId = row.petId,
                id = row.id,
                requestJson = rules.encodeRequest(terminalRequest),
                updatedAt = now
            ) == 1) { "arcade_receipt_claim_failed" }
            val result = TamaActionReceiptResult(
                success = true,
                action = TamaActionReceiptKind.ARCADE_SESSION,
                completedAt = now,
                rewardCoins = reward.coins,
                rewardHappiness = reward.happiness
            )
            // Exactly-once gate: only the call that completes the active row keeps the reward.
            check(dao.completeActive(
                petId = row.petId,
                id = row.id,
                status = TamaActionReceiptStatus.SUCCEEDED,
                updatedAt = now,
                completedAt = now,
                resultJson = rules.encodeResult(result)
            ) == 1) { "arcade_receipt_claim_failed" }
            rules.receipt(request, ArcadeReceiptStatus.SUCCEEDED, result)
        }
    }

    /** Releases an active session without awarding anything. */
    suspend fun cancelArcadeSession(request: ArcadeSessionRequest): ArcadeSessionReceipt {
        val rules = TamaArcadeSessionRules
        fun rejected() = rules.receipt(request, ArcadeReceiptStatus.REJECTED)
        if (request.petId.isBlank() || request.sessionId.isBlank() || request.gameId.isBlank()) return rejected()
        return transaction { dao ->
            val row = dao.byId(request.petId, request.sessionId) ?: return@transaction rejected()
            val stored = rules.decodeRequest(row.requestJson)
            if (stored == null || !rules.sameSession(stored, request)) return@transaction rejected()
            if (row.status in TamaActionReceiptStatus.terminal) {
                return@transaction rules.terminalReceipt(request, row)
            }
            if (row.status !in TamaActionReceiptStatus.active) return@transaction rejected()
            activePet(request.petId) ?: return@transaction rejected()
            rules.rejectActive(dao, row, "arcade_session_cancelled", clock())
            rejected()
        }
    }

    /** Recovers one active persisted lease after process death. */
    suspend fun reconcileArcadeSession(petId: String): ArcadeSessionLease? = transaction { dao ->
        val rules = TamaArcadeSessionRules
        activePet(petId) ?: return@transaction null
        val now = clock()
        val rows = dao.active(petId).filter { it.kind == TamaActionReceiptKind.ARCADE_SESSION }
        val row = rows.firstOrNull() ?: return@transaction null
        rows.drop(1).forEach { rules.rejectActive(dao, it, "arcade_duplicate_session", now) }
        val stored = rules.decodeRequest(row.requestJson)
        val session = stored?.let(rules::requestFromReceipt)
        if (stored == null || session == null || stored.receiptId != row.id ||
            stored.petId != row.petId || stored.kind != row.kind ||
            rules.validate(session) != null || !rules.sameSession(stored, session) ||
            now - row.updatedAt > TamaArcadeSessionRules.SESSION_TIMEOUT_MS || !atArcade()
        ) {
            rules.rejectActive(dao, row, "arcade_recovery_invalid", now)
            return@transaction null
        }
        if (row.status == TamaActionReceiptStatus.QUEUED) dao.markRunning(row.petId, row.id, now)
        rules.lease(session, ArcadeSessionLeaseStatus.RECONCILE_REQUIRED)
    }

    /**
     * Rebuilds the Arcade host after process death. Active leases take
     * precedence over terminal rows; a committed terminal success carries the
     * persisted metrics and reward so the UI can safely recreate its summary.
     */
    suspend fun recoverArcadeSession(petId: String): ArcadeSessionRecovery = TamaActionGate.run {
        val rules = TamaArcadeSessionRules
        val active = reconcileArcadeSession(petId)
        if (active != null) {
            return@run ArcadeSessionRecovery(ArcadeSessionRecoveryStatus.ACTIVE, activeLease = active)
        }
        val row = transaction { dao ->
            dao.unacknowledged(petId).firstOrNull { it.kind == TamaActionReceiptKind.ARCADE_SESSION }
        } ?: return@run ArcadeSessionRecovery()
        val terminal = rules.terminalSession(row)
        if (terminal != null) {
            return@run ArcadeSessionRecovery(ArcadeSessionRecoveryStatus.TERMINAL, terminal = terminal)
        }
        val stored = rules.decodeRequest(row.requestJson)
        val session = stored?.let(rules::requestFromReceipt)
        if (session != null && rules.validate(session) == null) {
            return@run ArcadeSessionRecovery(
                ArcadeSessionRecoveryStatus.TERMINAL,
                terminal = ArcadeSessionTerminal(session, rules.receipt(session, ArcadeReceiptStatus.REJECTED))
            )
        }
        ArcadeSessionRecovery(ArcadeSessionRecoveryStatus.UNAVAILABLE)
    }

    /** A terminal dialog is dismissed only after its durable receipt is acked. */
    suspend fun acknowledgeArcadeSession(request: ArcadeSessionRequest): Boolean {
        if (request.petId.isBlank() || request.sessionId.isBlank()) return false
        return transaction { dao ->
            val row = dao.byId(request.petId, request.sessionId) ?: return@transaction false
            val stored = TamaArcadeSessionRules.decodeRequest(row.requestJson)
            if (stored == null || stored.receiptId != row.id || stored.petId != row.petId ||
                stored.kind != row.kind || !TamaArcadeSessionRules.sameSession(stored, request)
            ) return@transaction false
            if (row.status == TamaActionReceiptStatus.ACKNOWLEDGED) return@transaction true
            if (row.status !in setOf(
                    TamaActionReceiptStatus.SUCCEEDED,
                    TamaActionReceiptStatus.REJECTED,
                    TamaActionReceiptStatus.FAILED
                )
            ) return@transaction false
            dao.acknowledge(request.petId, request.sessionId, clock()) == 1
        }
    }
}
