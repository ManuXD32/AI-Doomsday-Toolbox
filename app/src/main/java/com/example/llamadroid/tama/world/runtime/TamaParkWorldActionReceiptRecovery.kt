package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.world.core.WorldCommand
import com.example.llamadroid.tama.world.core.WorldEffectRequest
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind

/** Bounded cleanup for active receipts whose saved actor intent was replaced. */
internal object TamaParkWorldActionReceiptRecovery {
    const val ORPHAN_TIMEOUT_MS = 5L * 60L * 1_000L

    suspend fun rejectOrphans(
        state: WorldState,
        receipts: TamaParkWorldActionReceiptStore,
        now: Long
    ): Int {
        val referenced = referencedReceiptIds(state)
        var rejected = 0
        receipts.active(state.petId).filter { it.kind != TamaWorldActionReceiptKind.ARCADE_SESSION }.forEach { receipt ->
            if (receipt.status in TamaWorldActionReceiptStatus.active &&
                receipt.id !in referenced && now - receipt.updatedAt >= ORPHAN_TIMEOUT_MS
            ) {
                receipts.rejectActive(receipt, "world_action_orphaned", now)
                rejected++
            }
        }
        return rejected
    }

    suspend fun rejectForStop(
        state: WorldState,
        receipts: TamaParkWorldActionReceiptStore,
        command: WorldCommand,
        now: Long
    ): Int {
        if (command !is WorldCommand.Stop) return 0
        var rejected = 0
        val referenced = referencedReceiptIds(state)
        receipts.active(state.petId).filter {
            it.kind != TamaWorldActionReceiptKind.ARCADE_SESSION && it.id in referenced
        }.forEach { receipt ->
            receipts.rejectActive(receipt, "world_action_cancelled", now)
            rejected++
        }
        return rejected
    }

    suspend fun rejectReplaced(
        before: WorldState,
        after: WorldState,
        effects: List<WorldEffectRequest>,
        receipts: TamaParkWorldActionReceiptStore,
        now: Long
    ): Int {
        val completed = effects.asSequence()
            .filterIsInstance<WorldEffectRequest.CanonicalAction>()
            .filter { it.action == TamaParkWorldAction.CANONICAL_ACTION }
            .mapNotNull { it.arguments[RECEIPT_ID_ARGUMENT] }
            .toSet()
        val removed = referencedReceiptIds(before) - referencedReceiptIds(after) - completed
        var rejected = 0
        receipts.active(before.petId).filter {
            it.kind != TamaWorldActionReceiptKind.ARCADE_SESSION && it.id in removed
        }.forEach { receipt ->
            receipts.rejectActive(receipt, "world_action_replaced", now)
            rejected++
        }
        return rejected
    }

    private fun referencedReceiptIds(state: WorldState): Set<String> = buildSet {
        state.actor.actionArguments[RECEIPT_ID_ARGUMENT]?.let(::add)
        state.actor.pendingCommand?.arguments?.get(RECEIPT_ID_ARGUMENT)?.let(::add)
        state.actor.pendingActivity?.arguments?.get(RECEIPT_ID_ARGUMENT)?.let(::add)
    }

    private const val RECEIPT_ID_ARGUMENT = "worldReceiptId"
}
