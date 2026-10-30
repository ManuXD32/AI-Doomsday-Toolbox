package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.data.LegacyLocationAliases
// TG re-points these to com.example.llamadroid.tama.db.TamaActionReceipt* (wave 2).
import com.example.llamadroid.tama.db.TamaActionReceiptDao
import com.example.llamadroid.tama.db.TamaActionReceiptEntity
import com.example.llamadroid.tama.db.TamaActionReceiptKind
import com.example.llamadroid.tama.db.TamaActionReceiptRequest
import com.example.llamadroid.tama.db.TamaActionReceiptResult
import com.example.llamadroid.tama.db.TamaActionReceiptStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pure Arcade rules: validation, reward table and receipt (de)serialization. A session is admitted before the local minigame
 * starts and can pay only while its own active receipt is being completed.
 */
internal object TamaArcadeSessionRules {
    /** Stored in new rows; never checked on read, so legacy `world:<pet>` rows still replay. */
    const val CLASSIC_RECEIPT_WORLD_ID = "classic"
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
        now: Long,
        worldId: String = CLASSIC_RECEIPT_WORLD_ID,
        requestedAt: Long = now
    ): TamaActionReceiptRequest {
        return TamaActionReceiptRequest(
            receiptId = request.sessionId,
            petId = request.petId,
            worldId = worldId,
            kind = TamaActionReceiptKind.ARCADE_SESSION,
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

    fun requestFromReceipt(request: TamaActionReceiptRequest): ArcadeSessionRequest? {
        if (request.kind != TamaActionReceiptKind.ARCADE_SESSION ||
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
    fun sameSession(stored: TamaActionReceiptRequest, incoming: ArcadeSessionRequest): Boolean =
        stored.receiptId == incoming.sessionId && stored.petId == incoming.petId &&
            stored.kind == TamaActionReceiptKind.ARCADE_SESSION &&
            stored.destinationId == LegacyLocationAliases.ARCADE &&
            stored.targetNpcId == TARGET_ID && stored.arguments[GAME_ARGUMENT] == incoming.gameId

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

    fun lease(request: ArcadeSessionRequest, status: ArcadeSessionLeaseStatus): ArcadeSessionLease =
        ArcadeSessionLease(request.petId, request.sessionId, request.gameId, status)

    fun receipt(request: ArcadeSessionRequest, status: ArcadeReceiptStatus, result: TamaActionReceiptResult? = null): ArcadeSessionReceipt =
        ArcadeSessionReceipt(
            sessionId = request.sessionId,
            status = status,
            coins = result?.rewardCoins?.coerceIn(0L, 50L)?.toInt() ?: 0,
            happiness = result?.rewardHappiness?.coerceIn(0f, 12f)?.toInt() ?: 0
        )

    fun encodeRequest(request: TamaActionReceiptRequest): String = json.encodeToString(request)
    fun decodeRequest(value: String): TamaActionReceiptRequest? =
        runCatching { json.decodeFromString<TamaActionReceiptRequest>(value) }.getOrNull()
    fun encodeResult(result: TamaActionReceiptResult): String = json.encodeToString(result)
    fun decodeResult(value: String?): TamaActionReceiptResult? =
        value?.let { runCatching { json.decodeFromString<TamaActionReceiptResult>(it) }.getOrNull() }

    /**
     * Decodes only a fully committed, canonical terminal success. The request
     * stored in the receipt is authoritative after process death; a malformed
     * or tampered result is deliberately treated as a failed replay.
     */
    fun terminalSession(row: TamaActionReceiptEntity): ArcadeSessionTerminal? {
        if (row.status != TamaActionReceiptStatus.SUCCEEDED ||
            row.kind != TamaActionReceiptKind.ARCADE_SESSION
        ) return null
        val stored = decodeRequest(row.requestJson) ?: return null
        if (stored.petId != row.petId || stored.receiptId != row.id || stored.kind != row.kind) return null
        val request = requestFromReceipt(stored) ?: return null
        if (validate(request) != null || isIdentityOnly(request)) return null
        val result = decodeResult(row.resultJson) ?: return null
        if (!result.success || result.action != TamaActionReceiptKind.ARCADE_SESSION) return null
        val expected = rewardFor(request)
        if (result.rewardCoins != expected.coins || result.rewardHappiness != expected.happiness ||
            result.rewardCoins !in 0L..50L || result.rewardHappiness !in 0f..12f ||
            result.rewardHappiness != result.rewardHappiness.toInt().toFloat()
        ) return null
        return ArcadeSessionTerminal(request, receipt(request, ArcadeReceiptStatus.SUCCEEDED, result))
    }

    fun isIdentityOnly(request: ArcadeSessionRequest): Boolean =
        request.score == 0 && request.catches == 0 && request.misses == 0 &&
            request.totalObjects == 0 && request.pairsMatched == 0 && request.turnsUsed == 0

    fun terminalReceipt(request: ArcadeSessionRequest, row: TamaActionReceiptEntity): ArcadeSessionReceipt {
        if (row.petId != request.petId || row.id != request.sessionId ||
            row.kind != TamaActionReceiptKind.ARCADE_SESSION
        ) return receipt(request, ArcadeReceiptStatus.REJECTED)
        if (row.status != TamaActionReceiptStatus.SUCCEEDED) {
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
        dao: TamaActionReceiptDao,
        row: TamaActionReceiptEntity,
        errorCode: String,
        now: Long
    ): TamaActionReceiptEntity {
        val result = TamaActionReceiptResult(
            success = false,
            action = TamaActionReceiptKind.ARCADE_SESSION,
            errorCode = errorCode,
            completedAt = now
        )
        dao.completeActive(
            petId = row.petId,
            id = row.id,
            status = TamaActionReceiptStatus.REJECTED,
            updatedAt = now,
            completedAt = now,
            resultJson = encodeResult(result)
        )
        return dao.byId(row.petId, row.id) ?: row
    }
}
