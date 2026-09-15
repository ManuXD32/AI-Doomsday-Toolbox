package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptDao
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptRequest
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptResult
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Receipt admission is deliberately separate from the world action executor.
 * It compares the decoded request, rather than JSON text, so map ordering does
 * not create a false conflict. A reused id with different facts is rejected.
 */
internal class TamaParkWorldActionReceiptStore(
    private val dao: TamaWorldActionReceiptDao,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
) {
    private val parkKinds = setOf(
        TamaWorldActionReceiptKind.PARK_QUEST_ACCEPT,
        TamaWorldActionReceiptKind.PARK_QUEST_FINISH,
        TamaWorldActionReceiptKind.RECYCLER_HELP,
        TamaWorldActionReceiptKind.RECYCLER_FINISH,
        TamaWorldActionReceiptKind.RECYCLER_DECLINE,
        TamaWorldActionReceiptKind.SELLER_ACCEPT,
        TamaWorldActionReceiptKind.SELLER_SALE,
        TamaWorldActionReceiptKind.SELLER_DECLINE,
        TamaWorldActionReceiptKind.SELLER_FINISH
    )

    enum class Decision { CREATED, EXISTING_ACTIVE, EXISTING_TERMINAL, CONFLICT }

    data class Admission(
        val decision: Decision,
        val receipt: TamaWorldActionReceiptEntity,
        val reason: String? = null
    ) {
        val accepted: Boolean get() = decision != Decision.CONFLICT
    }

    suspend fun admit(request: TamaWorldActionReceiptRequest, now: Long): Admission {
        require(request.kind in parkKinds) {
            "unsupported_park_action"
        }
        val encoded = json.encodeToString(request)
        val candidate = TamaWorldActionReceiptEntity(
            petId = request.petId,
            id = request.receiptId,
            worldId = request.worldId,
            kind = request.kind,
            requestJson = encoded,
            createdAt = now,
            updatedAt = now
        )
        val (inserted, existing) = dao.createOrGet(candidate)
        if (!sameRequest(existing, request)) {
            return Admission(Decision.CONFLICT, existing, "receipt_request_conflict")
        }
        val decision = when {
            existing.status in TamaWorldActionReceiptStatus.terminal -> Decision.EXISTING_TERMINAL
            existing.status in TamaWorldActionReceiptStatus.active ->
                if (inserted) Decision.CREATED else Decision.EXISTING_ACTIVE
            else -> Decision.CONFLICT
        }
        return Admission(decision, existing, if (decision == Decision.CONFLICT) "receipt_status_invalid" else null)
    }

    /**
     * Returns only receipts owned by this adapter. The shared table also
     * carries other durable session types (for example Arcade leases), so
     * recovery must never terminalize a foreign active row.
     */
    suspend fun active(petId: String): List<TamaWorldActionReceiptEntity> =
        dao.active(petId).filter { it.kind in parkKinds }

    suspend fun rejectActive(
        receipt: TamaWorldActionReceiptEntity,
        errorCode: String,
        now: Long,
        message: String = ""
    ): TamaWorldActionReceiptEntity {
        val payload = json.encodeToString(
            TamaWorldActionReceiptResult(
                success = false,
                message = message,
                action = receipt.kind,
                errorCode = errorCode,
                completedAt = now
            )
        )
        dao.completeActive(
            petId = receipt.petId,
            id = receipt.id,
            status = TamaWorldActionReceiptStatus.REJECTED,
            updatedAt = now,
            completedAt = now,
            resultJson = payload
        )
        return dao.byId(receipt.petId, receipt.id) ?: receipt
    }

    suspend fun acknowledge(petId: String, receiptId: String, now: Long): Boolean =
        dao.acknowledge(petId, receiptId, now) == 1

    private fun sameRequest(
        entity: TamaWorldActionReceiptEntity,
        request: TamaWorldActionReceiptRequest
    ): Boolean {
        if (entity.petId != request.petId || entity.id != request.receiptId ||
            entity.worldId != request.worldId || entity.kind != request.kind
        ) return false
        return runCatching {
            json.decodeFromString<TamaWorldActionReceiptRequest>(entity.requestJson) == request
        }.getOrDefault(false)
    }
}
