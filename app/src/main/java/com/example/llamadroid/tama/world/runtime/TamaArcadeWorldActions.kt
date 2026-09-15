package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.ActionState
import com.example.llamadroid.tama.world.core.PendingActivityIntent
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptDao
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptRequest
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptResult
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import com.example.llamadroid.tama.world.presentation.ArcadeReceiptStatus
import com.example.llamadroid.tama.world.presentation.ArcadeSessionLease
import com.example.llamadroid.tama.world.presentation.ArcadeSessionLeaseStatus
import com.example.llamadroid.tama.world.presentation.ArcadeSessionReceipt
import com.example.llamadroid.tama.world.presentation.ArcadeSessionRequest
import com.example.llamadroid.tama.world.presentation.ArcadeSessionTerminal
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable Arcade boundary. A session is admitted before the local minigame
 * starts and can pay only while its own active receipt is being completed.
 */
internal object TamaArcadeWorldActions {
    const val LEASE_ACTION = "ARCADE_SESSION"
    const val SESSION_ARGUMENT = "arcadeSessionId"
    const val GAME_ARGUMENT = "arcadeGameId"
    const val TARGET_ID = "arcade_host"
    const val SESSION_TIMEOUT_MS = 30L * 60L * 1_000L

    private const val SCORE_ARGUMENT = "score"
    private const val CATCHES_ARGUMENT = "catches"
    private const val MISSES_ARGUMENT = "misses"
    private const val TOTAL_OBJECTS_ARGUMENT = "totalObjects"
    private const val PAIRS_ARGUMENT = "pairsMatched"
    private const val TURNS_ARGUMENT = "turnsUsed"
    private const val MEMORY_MAX_TURNS = 12
    private const val MAX_METRIC = 10_000
    private const val PREVIOUS_ACTION_ARGUMENT = "arcadePreviousAction"
    private const val PREVIOUS_STATE_ARGUMENT = "arcadePreviousActionState"
    private const val PREVIOUS_TICKS_ARGUMENT = "arcadePreviousActionTicks"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    data class Reward(val coins: Long, val happiness: Float)

    fun validate(request: ArcadeSessionRequest): String? {
        if (request.petId.isBlank() || request.sessionId.isBlank()) return "arcade_identity_required"
        if (request.gameId !in setOf("catch", "memory")) return "arcade_game_invalid"
        if (request.score !in 0..MAX_METRIC || request.catches !in 0..MAX_METRIC ||
            request.misses !in 0..MAX_METRIC || request.totalObjects !in 0..MAX_METRIC ||
            request.pairsMatched !in 0..MAX_METRIC || request.turnsUsed !in 0..MAX_METRIC
        ) return "arcade_metrics_invalid"
        // The UI begins with an identity-only request. Terminal metrics are
        // validated below before a reward can be applied.
        if (request.score == 0 && request.catches == 0 && request.misses == 0 &&
            request.totalObjects == 0 && request.pairsMatched == 0 && request.turnsUsed == 0
        ) return null
        return when (request.gameId) {
            "catch" -> when {
                request.totalObjects <= 0 -> "arcade_objects_required"
                request.catches > request.totalObjects || request.misses > request.totalObjects ->
                    "arcade_metrics_invalid"
                request.catches + request.misses != request.totalObjects -> "arcade_metrics_invalid"
                request.score != request.catches -> "arcade_score_invalid"
                else -> null
            }
            "memory" -> when {
                request.totalObjects != 8 -> "arcade_deck_invalid"
                request.pairsMatched !in 0..request.totalObjects -> "arcade_metrics_invalid"
                request.turnsUsed !in 1..MEMORY_MAX_TURNS -> "arcade_turns_invalid"
                request.score != request.pairsMatched * 10 -> "arcade_score_invalid"
                else -> null
            }
            else -> "arcade_game_invalid"
        }
    }

    fun receiptRequest(
        request: ArcadeSessionRequest,
        worldId: String,
        now: Long,
        requestedAt: Long = now
    ): TamaWorldActionReceiptRequest {
        return TamaWorldActionReceiptRequest(
            receiptId = request.sessionId,
            petId = request.petId,
            worldId = worldId,
            kind = TamaWorldActionReceiptKind.ARCADE_SESSION,
            destinationId = LegacyLocationAliases.ARCADE,
            targetNpcId = TARGET_ID,
            requestedAt = requestedAt,
            arguments = mapOf(
                GAME_ARGUMENT to request.gameId,
                SCORE_ARGUMENT to request.score.toString(),
                CATCHES_ARGUMENT to request.catches.toString(),
                MISSES_ARGUMENT to request.misses.toString(),
                TOTAL_OBJECTS_ARGUMENT to request.totalObjects.toString(),
                PAIRS_ARGUMENT to request.pairsMatched.toString(),
                TURNS_ARGUMENT to request.turnsUsed.toString()
            )
        )
    }

    fun requestFromReceipt(request: TamaWorldActionReceiptRequest): ArcadeSessionRequest? {
        if (request.kind != TamaWorldActionReceiptKind.ARCADE_SESSION ||
            request.destinationId != LegacyLocationAliases.ARCADE || request.targetNpcId != TARGET_ID
        ) return null
        fun int(name: String): Int? = request.arguments[name]?.toIntOrNull()
        return ArcadeSessionRequest(
            petId = request.petId,
            sessionId = request.receiptId,
            gameId = request.arguments[GAME_ARGUMENT].orEmpty(),
            score = int(SCORE_ARGUMENT) ?: return null,
            catches = int(CATCHES_ARGUMENT) ?: return null,
            misses = int(MISSES_ARGUMENT) ?: return null,
            totalObjects = int(TOTAL_OBJECTS_ARGUMENT) ?: return null,
            pairsMatched = int(PAIRS_ARGUMENT) ?: return null,
            turnsUsed = int(TURNS_ARGUMENT) ?: return null
        )
    }

    /** Immutable identity checks allow begin (zero metrics) then submit (terminal metrics). */
    fun sameSession(stored: TamaWorldActionReceiptRequest, incoming: ArcadeSessionRequest): Boolean =
        stored.receiptId == incoming.sessionId && stored.petId == incoming.petId &&
            stored.kind == TamaWorldActionReceiptKind.ARCADE_SESSION &&
            stored.destinationId == LegacyLocationAliases.ARCADE &&
            stored.targetNpcId == TARGET_ID && stored.arguments[GAME_ARGUMENT] == incoming.gameId

    fun belongsToWorld(request: TamaWorldActionReceiptRequest, worldId: String): Boolean =
        request.worldId == worldId

    fun rewardFor(request: ArcadeSessionRequest): Reward {
        val validation = validate(request)
        require(validation == null) { validation ?: "arcade_metrics_invalid" }
        val coins = when (request.gameId) {
            "catch" -> when {
                request.catches <= 4 -> 0L
                request.catches <= 8 -> 10L
                request.catches <= 12 -> 20L
                request.catches <= 16 -> 30L
                request.catches >= request.totalObjects && request.totalObjects > 0 -> 50L
                request.catches >= 17 -> 40L
                else -> 0L
            }
            "memory" -> when {
                request.pairsMatched <= 0 -> 0L
                request.pairsMatched >= 8 && request.turnsUsed < MEMORY_MAX_TURNS -> 50L
                else -> (request.pairsMatched.coerceAtMost(8) * 5).coerceAtMost(40).toLong()
            }
            else -> 0L
        }
        val happiness = when {
            request.gameId == "memory" && request.pairsMatched >= 8 && request.turnsUsed < MEMORY_MAX_TURNS -> 12f
            coins >= 50L -> 12f
            coins > 0L -> 10f
            else -> 8f
        }
        return Reward(coins, happiness)
    }

    private fun leaseIntent(sessionId: String, state: WorldState): PendingActivityIntent = PendingActivityIntent(
        action = LEASE_ACTION,
        destinationId = LegacyLocationAliases.ARCADE,
        arguments = mapOf(
            SESSION_ARGUMENT to sessionId,
            PREVIOUS_ACTION_ARGUMENT to state.actor.action.name,
            PREVIOUS_STATE_ARGUMENT to state.actor.actionState.name,
            PREVIOUS_TICKS_ARGUMENT to state.actor.actionTicksRemaining.toString()
        ),
        blocksPetSimulation = true
    )

    fun leaseSessionId(state: WorldState): String? = state.actor.pendingActivity
        ?.takeIf { it.action == LEASE_ACTION && it.destinationId == LegacyLocationAliases.ARCADE }
        ?.arguments?.get(SESSION_ARGUMENT)?.takeIf { it.isNotBlank() }

    fun atArcade(state: WorldState): Boolean = state.actor.presence == PresenceMode.INTERIOR &&
        state.actor.structureId == LegacyLocationAliases.ARCADE

    fun withLease(state: WorldState, sessionId: String): WorldState {
        val actor = state.actor
        // Recovery and repeated begin calls may project the same lease more
        // than once. Preserve the original action snapshot in the intent so
        // release can restore the action that was active before the lease.
        if (leaseSessionId(state) == sessionId) {
            return state.copy(actor = actor.copy(
                action = ActionId.USE_ARCADE,
                actionState = ActionState.RUNNING,
                actionTicksRemaining = 0,
                pendingActivity = actor.pendingActivity?.copy(blocksPetSimulation = true)
            ))
        }
        return state.copy(actor = actor.copy(
            action = ActionId.USE_ARCADE,
            actionState = ActionState.RUNNING,
            actionTicksRemaining = 0,
            actionArguments = emptyMap(),
            pendingActivity = leaseIntent(sessionId, state)
        ))
    }

    fun clearLease(state: WorldState, sessionId: String? = null): WorldState {
        val intent = state.actor.pendingActivity
            ?.takeIf { it.action == LEASE_ACTION && it.destinationId == LegacyLocationAliases.ARCADE }
        if (intent == null) return state
        val current = intent.arguments[SESSION_ARGUMENT]
        if (sessionId != null && current != sessionId) return state
        val action = intent.arguments[PREVIOUS_ACTION_ARGUMENT]
            ?.let { runCatching { ActionId.valueOf(it) }.getOrNull() } ?: ActionId.WAIT
        val actionState = intent.arguments[PREVIOUS_STATE_ARGUMENT]
            ?.let { runCatching { ActionState.valueOf(it) }.getOrNull() } ?: ActionState.IDLE
        val ticks = intent.arguments[PREVIOUS_TICKS_ARGUMENT]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return state.copy(actor = state.actor.copy(
            action = action,
            actionState = actionState,
            actionTicksRemaining = ticks,
            actionArguments = emptyMap(),
            pendingActivity = null
        ))
    }

    /** Restores or invalidates a persisted lease before the world can tick again. */
    suspend fun restoreLease(
        state: WorldState,
        dao: TamaWorldActionReceiptDao,
        now: Long
    ): WorldState {
        var restored = state
        val rows = dao.active(state.petId)
            .filter { it.kind == TamaWorldActionReceiptKind.ARCADE_SESSION }
        val row = rows.firstOrNull()
        rows.drop(1).forEach { rejectActive(dao, it, "arcade_duplicate_session", now) }
        if (row == null) return clearLease(restored)
        val request = decodeRequest(row.requestJson)
        val session = request?.let(::requestFromReceipt)
        if (request == null || session == null || request.receiptId != row.id ||
            request.petId != row.petId || request.kind != row.kind || !sameSession(request, session) ||
            !belongsToWorld(request, restored.worldId) || validate(session) != null ||
            now - row.updatedAt > SESSION_TIMEOUT_MS || !atArcade(restored)
        ) {
            rejectActive(dao, row, when {
                session == null -> "arcade_receipt_invalid"
                now - row.updatedAt > SESSION_TIMEOUT_MS -> "arcade_session_expired"
                !atArcade(restored) -> "arcade_presence_lost"
                else -> "arcade_receipt_invalid"
            }, now)
            return clearLease(restored, session?.sessionId ?: row.id)
        }
        if (row.status == TamaWorldActionReceiptStatus.QUEUED) dao.markRunning(row.petId, row.id, now)
        return withLease(restored, session.sessionId)
    }

    fun lease(request: ArcadeSessionRequest, status: ArcadeSessionLeaseStatus): ArcadeSessionLease =
        ArcadeSessionLease(request.petId, request.sessionId, request.gameId, status)

    fun receipt(request: ArcadeSessionRequest, status: ArcadeReceiptStatus, result: TamaWorldActionReceiptResult? = null): ArcadeSessionReceipt =
        ArcadeSessionReceipt(
            sessionId = request.sessionId,
            status = status,
            coins = result?.rewardCoins?.coerceIn(0L, 50L)?.toInt() ?: 0,
            happiness = result?.rewardHappiness?.coerceIn(0f, 12f)?.toInt() ?: 0
        )

    fun encodeRequest(request: TamaWorldActionReceiptRequest): String = json.encodeToString(request)
    fun decodeRequest(value: String): TamaWorldActionReceiptRequest? =
        runCatching { json.decodeFromString<TamaWorldActionReceiptRequest>(value) }.getOrNull()
    fun encodeResult(result: TamaWorldActionReceiptResult): String = json.encodeToString(result)
    fun decodeResult(value: String?): TamaWorldActionReceiptResult? =
        value?.let { runCatching { json.decodeFromString<TamaWorldActionReceiptResult>(it) }.getOrNull() }

    /**
     * Decodes only a fully committed, canonical terminal success. The request
     * stored in the receipt is authoritative after process death; a malformed
     * or tampered result is deliberately treated as a failed replay.
     */
    fun terminalSession(row: TamaWorldActionReceiptEntity): ArcadeSessionTerminal? {
        if (row.status != TamaWorldActionReceiptStatus.SUCCEEDED ||
            row.kind != TamaWorldActionReceiptKind.ARCADE_SESSION
        ) return null
        val stored = decodeRequest(row.requestJson) ?: return null
        if (stored.petId != row.petId || stored.receiptId != row.id || stored.kind != row.kind) return null
        val request = requestFromReceipt(stored) ?: return null
        if (!belongsToWorld(stored, row.worldId) || validate(request) != null || isIdentityOnly(request)) return null
        val result = decodeResult(row.resultJson) ?: return null
        if (!result.success || result.action != TamaWorldActionReceiptKind.ARCADE_SESSION) return null
        val expected = rewardFor(request)
        if (result.rewardCoins != expected.coins || result.rewardHappiness != expected.happiness ||
            result.rewardCoins !in 0L..50L || result.rewardHappiness !in 0f..12f ||
            result.rewardHappiness != result.rewardHappiness.toInt().toFloat()
        ) return null
        return ArcadeSessionTerminal(request, receipt(request, ArcadeReceiptStatus.SUCCEEDED, result))
    }

    private fun isIdentityOnly(request: ArcadeSessionRequest): Boolean =
        request.score == 0 && request.catches == 0 && request.misses == 0 &&
            request.totalObjects == 0 && request.pairsMatched == 0 && request.turnsUsed == 0

    fun terminalReceipt(request: ArcadeSessionRequest, row: TamaWorldActionReceiptEntity): ArcadeSessionReceipt {
        if (row.petId != request.petId || row.id != request.sessionId ||
            row.kind != TamaWorldActionReceiptKind.ARCADE_SESSION
        ) return receipt(request, ArcadeReceiptStatus.REJECTED)
        if (row.status != TamaWorldActionReceiptStatus.SUCCEEDED) {
            return receipt(request, ArcadeReceiptStatus.REJECTED)
        }
        val terminal = terminalSession(row)
        val stored = decodeRequest(row.requestJson)
        return if (terminal != null && stored != null && sameSession(stored, request)) {
            terminal.receipt
        } else receipt(request, ArcadeReceiptStatus.FAILED)
    }

    /** Small DAO helper kept here so active replay and expiry use identical payloads. */
    suspend fun rejectActive(
        dao: TamaWorldActionReceiptDao,
        row: TamaWorldActionReceiptEntity,
        errorCode: String,
        now: Long
    ): TamaWorldActionReceiptEntity {
        val result = TamaWorldActionReceiptResult(
            success = false,
            action = TamaWorldActionReceiptKind.ARCADE_SESSION,
            errorCode = errorCode,
            completedAt = now
        )
        dao.completeActive(
            petId = row.petId,
            id = row.id,
            status = TamaWorldActionReceiptStatus.REJECTED,
            updatedAt = now,
            completedAt = now,
            resultJson = encodeResult(result)
        )
        return dao.byId(row.petId, row.id) ?: row
    }
}
