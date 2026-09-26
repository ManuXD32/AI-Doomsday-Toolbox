package com.example.llamadroid.tama.world.runtime

import androidx.room.withTransaction
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.TamaActionGate
import com.example.llamadroid.tama.game.TamaCommitEffects
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptRequest
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * UI-facing admission boundary. The returned state is QUEUED until the world
 * executor has physically completed the action. A completion dialog must read
 * the terminal receipt, never this queue result.
 */
internal class TamaParkWorldActionQueue(
    private val controller: TamaWorldController,
    private val database: TamaDatabase,
    private val receipts: TamaParkWorldActionReceiptStore,
    private val now: () -> Long = System::currentTimeMillis
) {
    data class Result(
        val accepted: Boolean,
        val receiptId: String,
        val status: String,
        val reason: String? = null
    )

    suspend fun queue(request: TamaWorldActionReceiptRequest): Result = TamaActionGate.run {
        TamaParkWorldAction.validateRequest(request)?.let {
            return@run Result(false, request.receiptId, TamaWorldActionReceiptStatus.REJECTED, it)
        }
        val current = controller.state.value ?: return@run Result(
            false,
            request.receiptId,
            TamaWorldActionReceiptStatus.REJECTED,
            "world_not_ready"
        )
        if (current.petId != request.petId || current.worldId != request.worldId) {
            return@run Result(
                false,
                request.receiptId,
                TamaWorldActionReceiptStatus.REJECTED,
                "world_ownership_mismatch"
            )
        }
        // Receipt admission and actor intent persistence share one Room
        // transaction. A process death cannot leave an active row without a
        // command that references it.
        val timestamp = now()
        try {
            // The controller may publish its in-memory state while this
            // callback is still inside Room's outer transaction. The commit
            // effect context postpones external delivery, and the recovery
            // path below reloads both snapshots if the outer commit fails.
            TamaCommitEffects.afterCommit {
                database.withTransaction {
                    val admission = receipts.admit(request, timestamp)
                    if (!admission.accepted) {
                        return@withTransaction Result(false, request.receiptId,
                            admission.receipt.status, admission.reason)
                    }
                    if (admission.decision == TamaParkWorldActionReceiptStore.Decision.EXISTING_TERMINAL ||
                        admission.decision == TamaParkWorldActionReceiptStore.Decision.EXISTING_ACTIVE
                    ) return@withTransaction Result(true, request.receiptId, admission.receipt.status)

                    val queued = controller.queueAction(
                        action = TamaParkWorldAction.coreAction(request.kind),
                        targetId = request.targetNpcId,
                        arguments = TamaParkWorldAction.arguments(request),
                        destinationId = request.destinationId
                    )
                    if (!queued.acceptedCommand) {
                        receipts.rejectActive(
                            admission.receipt,
                            errorCode = queued.rejectionReason ?: "world_command_rejected",
                            now = timestamp
                        )
                        return@withTransaction Result(false, request.receiptId,
                            TamaWorldActionReceiptStatus.REJECTED,
                            queued.rejectionReason ?: "world_command_rejected")
                    }
                    Result(true, request.receiptId, TamaWorldActionReceiptStatus.QUEUED)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) { controller.recoverAfterParkQueueFailure() }
            throw cancelled
        } catch (_: Exception) {
            // Room has rolled back before this handler runs. A nested
            // controller.accept may already have published its candidate;
            // invalidate/reload before returning so a later clock tick cannot
            // flush that candidate over the rolled-back database state.
            controller.recoverAfterParkQueueFailure()
            Result(false, request.receiptId, TamaWorldActionReceiptStatus.REJECTED,
                "world_transaction_failed")
        }
    }
}
