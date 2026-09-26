package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.world.core.WorldEffectRequest
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptDao
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptRequest
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptResult
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset

/**
 * Executes Park legacy effects after the pure core has completed a physical
 * adjacency action. Call this from WorldEffectCommitter while it is already in
 * the controller's Room transaction. The receipt status claim and the locked
 * pet mutation therefore commit or roll back together.
 */
internal class TamaParkWorldActionExecutor(
    private val receipts: TamaWorldActionReceiptDao,
    private val legacy: TamaParkWorldActionPort,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
) {
    data class Completion(
        val domainAccepted: Boolean,
        val result: TamaWorldActionReceiptResult
    )

    /**
     * A domain rejection is returned to the committer without throwing. An
     * unexpected exception is allowed to escape so the enclosing transaction
     * rolls back both the receipt claim and canonical pet state for retry.
     */
    suspend fun completeInsideTransaction(
        state: WorldState,
        effect: WorldEffectRequest.CanonicalAction,
        now: Long
    ): Completion {
        if (effect.action != TamaParkWorldAction.CANONICAL_ACTION) {
            return rejected(null, "wrong_canonical_action", now)
        }
        val receiptId = effect.arguments[TamaParkWorldAction.RECEIPT_ID_ARGUMENT]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return rejected(null, "receipt_id_required", now)
        val row = receipts.byId(state.petId, receiptId)
            ?: return rejected(null, "receipt_missing", now)
        if (row.status in TamaWorldActionReceiptStatus.terminal) return replay(row)
        if (row.status == TamaWorldActionReceiptStatus.RUNNING) {
            // A committed RUNNING row is anomalous: normal completion claims
            // and finishes in one Room transaction. Never guess whether a
            // prior process committed the canonical reward.
            return rejectActive(row, "receipt_recovery_required", now)
        }

        val request = runCatching {
            json.decodeFromString<TamaWorldActionReceiptRequest>(row.requestJson)
        }.getOrElse {
            return rejectActive(row, "receipt_request_invalid", now)
        }
        if (request.receiptId != row.id || request.petId != row.petId ||
            request.worldId != row.worldId || request.kind != row.kind ||
            request.receiptId != receiptId ||
            effect.arguments[TamaParkWorldAction.KIND_ARGUMENT] != request.kind
        ) return rejectActive(row, "receipt_identity_mismatch", now)

        TamaParkWorldAction.validateWorld(state, request, now)?.let {
            return rejectActive(row, it, now)
        }

        // The controller's `pet` parameter is read from Room, but the engine
        // flow may belong to another observer instance. Reload before calling
        // private stateful logic so this transaction checks current inventory,
        // encounter phase, quest status, and expiry.
        legacy.refreshFromCanonicalStore()
        val pet = legacy.currentPet() ?: return rejectActive(row, "pet_missing", now)
        if (pet.id != request.petId) return rejectActive(row, "pet_ownership_mismatch", now)
        TamaParkWorldAction.validateEncounter(pet, request)?.let {
            return rejectActive(row, it, now)
        }
        if (request.kind.startsWith("PARK_QUEST_")) {
            val questId = request.questId ?: return rejectActive(row, "quest_id_required", now)
            val questNpc = legacy.questNpcIdLocked(questId)
                ?: return rejectActive(row, "quest_not_found", now)
            if (questNpc != request.targetNpcId) {
                return rejectActive(row, "quest_counterparty_mismatch", now)
            }
        }

        // This update is the only claim. The caller must not commit RUNNING in
        // a separate transaction: a crash after that commit could replay a
        // reward whose canonical mutation was already committed or rolled back.
        if (row.status == TamaWorldActionReceiptStatus.QUEUED &&
            receipts.markRunning(row.petId, row.id, now) != 1
        ) return replay(receipts.byId(row.petId, row.id) ?: row)

        val legacy = executeLocked(request, now, state.timezoneOffsetMinutes)
        val result = TamaWorldActionReceiptResult(
            success = legacy.success,
            message = legacy.message,
            action = request.kind,
            errorCode = legacy.errorCode,
            completedAt = now,
            questPresentation = legacy.questPresentation
        )
        val status = if (result.success) TamaWorldActionReceiptStatus.SUCCEEDED
        else TamaWorldActionReceiptStatus.REJECTED
        val changed = receipts.completeActive(
            petId = row.petId,
            id = row.id,
            status = status,
            updatedAt = now,
            completedAt = now,
            resultJson = json.encodeToString(result)
        )
        if (changed == 1) return Completion(result.success, result)
        return replay(receipts.byId(row.petId, row.id) ?: row)
    }

    private suspend fun executeLocked(
        request: TamaWorldActionReceiptRequest,
        now: Long,
        timezoneOffsetMinutes: Int
    ): LegacyOutcome = when (request.kind) {
        TamaWorldActionReceiptKind.PARK_QUEST_ACCEPT ->
            legacy.acceptQuestLocked(requireNotNull(request.questId), now).asLegacy()
        TamaWorldActionReceiptKind.PARK_QUEST_FINISH ->
            legacy.finishQuestLocked(requireNotNull(request.questId), now).asLegacy()
        TamaWorldActionReceiptKind.RECYCLER_HELP ->
            legacy.acceptRecyclerLocked(now, localDateKey(now, timezoneOffsetMinutes)).asLegacy()
        TamaWorldActionReceiptKind.RECYCLER_FINISH ->
            legacy.finishRecyclerLocked().asLegacy()
        TamaWorldActionReceiptKind.RECYCLER_DECLINE ->
            legacy.declineRecyclerLocked(now, localDateKey(now, timezoneOffsetMinutes)).asLegacy()
        TamaWorldActionReceiptKind.SELLER_ACCEPT ->
            legacy.acceptSellerLocked().asLegacy()
        TamaWorldActionReceiptKind.SELLER_SALE -> {
            val itemId = requireNotNull(request.itemId)
            val quantity = requireNotNull(request.quantity)
            legacy.sellerSaleLocked(itemId, quantity).asLegacy()
        }
        TamaWorldActionReceiptKind.SELLER_DECLINE ->
            legacy.declineSellerLocked().asLegacy()
        TamaWorldActionReceiptKind.SELLER_FINISH ->
            legacy.finishSellerLocked().asLegacy()
        else -> LegacyOutcome(false, "", "unsupported_park_action")
    }

    private suspend fun rejectActive(
        row: TamaWorldActionReceiptEntity,
        errorCode: String,
        now: Long,
        message: String = ""
    ): Completion {
        val result = TamaWorldActionReceiptResult(
            success = false,
            message = message,
            action = row.kind,
            errorCode = errorCode,
            completedAt = now
        )
        val changed = receipts.completeActive(
            petId = row.petId,
            id = row.id,
            status = TamaWorldActionReceiptStatus.REJECTED,
            updatedAt = now,
            completedAt = now,
            resultJson = json.encodeToString(result)
        )
        if (changed == 1) return Completion(false, result)
        return replay(receipts.byId(row.petId, row.id) ?: row)
    }

    private fun rejected(
        row: TamaWorldActionReceiptEntity?,
        errorCode: String,
        now: Long
    ): Completion {
        // There is no safe row to mutate for a missing/corrupt receipt. The
        // committer records the rejection in its normal world diagnostics and
        // emits no canonical reward. Existing rows go through rejectActive.
        return Completion(false, TamaWorldActionReceiptResult(
            success = false, action = row?.kind.orEmpty(), errorCode = errorCode, completedAt = now
        ))
    }

    private fun replay(row: TamaWorldActionReceiptEntity): Completion {
        val result = row.resultJson?.let { encoded ->
            runCatching { json.decodeFromString<TamaWorldActionReceiptResult>(encoded) }.getOrNull()
        } ?: TamaWorldActionReceiptResult(
            success = false,
            action = row.kind,
            errorCode = "terminal_result_missing",
            completedAt = row.completedAt
        )
        return Completion(result.success, result)
    }

    private data class LegacyOutcome(
        val success: Boolean,
        val message: String,
        val errorCode: String? = null,
        val questPresentation: com.example.llamadroid.tama.world.persistence.TamaWorldQuestCompletionPresentation? = null
    )

    private fun TamaParkLegacyOutcome.asLegacy() = LegacyOutcome(
        success = success,
        message = message,
        errorCode = errorCode,
        questPresentation = questPresentation
    )

    private fun localDateKey(now: Long, timezoneOffsetMinutes: Int): String =
        Instant.ofEpochMilli(now)
            .atOffset(ZoneOffset.ofTotalSeconds(timezoneOffsetMinutes.coerceIn(-1_080, 1_080) * 60))
            .toLocalDate()
            .toString()
}
