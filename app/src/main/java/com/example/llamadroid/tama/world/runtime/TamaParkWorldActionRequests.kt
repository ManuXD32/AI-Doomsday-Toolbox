package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptRequest

/** Builders keep UI callers from selecting an invalid Park counterpart. */
object TamaParkWorldActionRequests {
    fun questAccept(
        receiptId: String,
        petId: String,
        worldId: String,
        questId: String,
        npcId: String,
        requestedAt: Long
    ) = request(receiptId, petId, worldId, TamaWorldActionReceiptKind.PARK_QUEST_ACCEPT,
        npcId, requestedAt, questId = questId)

    fun questFinish(
        receiptId: String,
        petId: String,
        worldId: String,
        questId: String,
        npcId: String,
        requestedAt: Long
    ) = request(receiptId, petId, worldId, TamaWorldActionReceiptKind.PARK_QUEST_FINISH,
        npcId, requestedAt, questId = questId)

    fun recycler(
        receiptId: String,
        petId: String,
        worldId: String,
        kind: String,
        requestedAt: Long
    ) = request(receiptId, petId, worldId, kind, "recycler", requestedAt)

    fun sellerDialogue(
        receiptId: String,
        petId: String,
        worldId: String,
        kind: String,
        requestedAt: Long
    ) = request(receiptId, petId, worldId, kind, "seller", requestedAt)

    fun sellerSale(
        receiptId: String,
        petId: String,
        worldId: String,
        itemId: String,
        quantity: Int,
        requestedAt: Long
    ) = request(receiptId, petId, worldId, TamaWorldActionReceiptKind.SELLER_SALE,
        "seller", requestedAt, itemId = itemId, quantity = quantity)

    private fun request(
        receiptId: String,
        petId: String,
        worldId: String,
        kind: String,
        targetNpcId: String,
        requestedAt: Long,
        questId: String? = null,
        itemId: String? = null,
        quantity: Int? = null
    ) = TamaWorldActionReceiptRequest(
        receiptId = receiptId,
        petId = petId,
        worldId = worldId,
        kind = kind,
        destinationId = TamaParkWorldAction.destinationId(kind),
        targetNpcId = targetNpcId,
        requestedAt = requestedAt,
        questId = questId,
        itemId = itemId,
        quantity = quantity
    )
}
