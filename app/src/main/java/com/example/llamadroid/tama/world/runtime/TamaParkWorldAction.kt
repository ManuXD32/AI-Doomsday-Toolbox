package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.data.TamaParkEncounterPhase
import com.example.llamadroid.tama.data.TamaParkEncounterType
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import com.example.llamadroid.tama.world.core.NpcRole
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.WorldClock
import com.example.llamadroid.tama.world.core.WorldNpc
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.core.WorldStructure
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptRequest

/**
 * Saved park requests all use one core canonical effect. The persisted receipt
 * supplies the mutable item/quest data again at completion; actor arguments only
 * identify the receipt and cannot grant a caller-controlled reward.
 */
object TamaParkWorldAction {
    const val CANONICAL_ACTION = "parkWorldReceipt"
    const val RECEIPT_ID_ARGUMENT = "worldReceiptId"
    const val KIND_ARGUMENT = "worldActionKind"

    fun coreAction(kind: String): ActionId = when (kind) {
        TamaWorldActionReceiptKind.PARK_QUEST_ACCEPT,
        TamaWorldActionReceiptKind.PARK_QUEST_FINISH,
        TamaWorldActionReceiptKind.SELLER_ACCEPT,
        TamaWorldActionReceiptKind.SELLER_FINISH -> ActionId.TALK
        TamaWorldActionReceiptKind.RECYCLER_HELP,
        TamaWorldActionReceiptKind.RECYCLER_FINISH -> ActionId.HELP_NPC
        TamaWorldActionReceiptKind.RECYCLER_DECLINE,
        TamaWorldActionReceiptKind.SELLER_DECLINE -> ActionId.SAY_GOODBYE
        TamaWorldActionReceiptKind.SELLER_SALE -> ActionId.TRADE
        else -> error("unsupported_park_action:$kind")
    }

    fun destinationId(kind: String): String = when (kind) {
        TamaWorldActionReceiptKind.SELLER_SALE,
        TamaWorldActionReceiptKind.SELLER_ACCEPT,
        TamaWorldActionReceiptKind.SELLER_FINISH,
        TamaWorldActionReceiptKind.SELLER_DECLINE -> "market_stall"
        else -> LegacyLocationAliases.PARK
    }

    fun arguments(request: TamaWorldActionReceiptRequest): Map<String, String> = mapOf(
        "canonicalAction" to CANONICAL_ACTION,
        RECEIPT_ID_ARGUMENT to request.receiptId,
        KIND_ARGUMENT to request.kind
    )

    fun validateRequest(request: TamaWorldActionReceiptRequest): String? {
        if (request.destinationId != destinationId(request.kind)) return "wrong_destination"
        if (request.targetNpcId != expectedNpcId(request.kind) && request.kind in
            setOf(
                TamaWorldActionReceiptKind.RECYCLER_HELP,
                TamaWorldActionReceiptKind.RECYCLER_FINISH,
                TamaWorldActionReceiptKind.RECYCLER_DECLINE,
                TamaWorldActionReceiptKind.SELLER_ACCEPT,
                TamaWorldActionReceiptKind.SELLER_SALE,
                TamaWorldActionReceiptKind.SELLER_DECLINE,
                TamaWorldActionReceiptKind.SELLER_FINISH
            )
        ) return "wrong_counterparty"
        if (request.kind == TamaWorldActionReceiptKind.SELLER_SALE &&
            (request.itemId.isNullOrBlank() || request.quantity == null || request.quantity <= 0)
        ) return "sale_selection_required"
        return null
    }

    /** Checks only world-owned facts. Quest/inventory/phase checks run again in the locked adapter. */
    fun validateWorld(state: WorldState, request: TamaWorldActionReceiptRequest, now: Long): String? {
        validateRequest(request)?.let { return it }
        if (state.petId != request.petId || state.worldId != request.worldId ||
            state.actor.actorId != request.petId
        ) return "world_ownership_mismatch"
        if (state.actor.presence != PresenceMode.WORLD) return "world_presence_required"
        val npc = state.npcs.firstOrNull { it.id == request.targetNpcId } ?: return "counterparty_missing"
        val park = state.structures.firstOrNull { it.id == LegacyLocationAliases.PARK }
        val market = state.structures.firstOrNull { it.id == "market_stall" }
        if ((request.kind.startsWith("SELLER_") && market == null) ||
            (!request.kind.startsWith("SELLER_") && park == null)
        ) return "park_structure_missing"
        if (request.kind.startsWith("RECYCLER_") && npc.role != NpcRole.RECYCLER) return "wrong_counterparty"
        if (request.kind.startsWith("SELLER_") && npc.role != NpcRole.MARKET_SELLER) return "wrong_counterparty"
        // `jobStructureId` is a fixed assignment, not proof that the NPC is
        // physically there. Use the live coordinate around the actual
        // structure entrance/footprint so a transient schedule label cannot
        // make a remote NPC eligible for a reward.
        if (request.kind.startsWith("RECYCLER_") && park != null && !isAtStructure(npc, park)) {
            return "counterparty_not_at_park"
        }
        if (request.kind.startsWith("SELLER_") && market != null && !isAtStructure(npc, market)) {
            return "counterparty_not_at_market"
        }
        if (state.actor.coordinate.chebyshevDistanceTo(npc.coordinate) > 1) return "counterparty_not_adjacent"
        val clock = WorldClock.at(now, state.timezoneOffsetMinutes)
        when {
            request.kind.startsWith("RECYCLER_") &&
                (clock.dayOfMonth !in setOf(10, 20, 30) || clock.minuteOfDay !in 480..960) ->
                return "recycler_out_of_window"
            request.kind.startsWith("SELLER_") &&
                (clock.dayOfWeek != 4 || clock.minuteOfDay !in 480..1_020) ->
                return "seller_out_of_window"
        }
        return null
    }

    private fun isAtStructure(npc: WorldNpc, structure: WorldStructure): Boolean =
        structure.contains(npc.coordinate) || npc.coordinate.chebyshevDistanceTo(structure.entrance) <= 1

    /** Legacy encounter phase remains canonical, but is checked immediately before effects. */
    fun validateEncounter(pet: TamaPet, request: TamaWorldActionReceiptRequest): String? {
        val encounter = pet.currentParkEncounter
        if (request.kind.startsWith("PARK_QUEST_")) return null
        if (encounter == null || encounter.npcId != request.targetNpcId) return "encounter_missing"
        return when (request.kind) {
            TamaWorldActionReceiptKind.RECYCLER_HELP,
            TamaWorldActionReceiptKind.RECYCLER_DECLINE ->
                if (encounter.type != TamaParkEncounterType.RECYCLER || encounter.phase != TamaParkEncounterPhase.INTRO) {
                    "recycler_phase_invalid"
                } else null
            TamaWorldActionReceiptKind.RECYCLER_FINISH ->
                if (encounter.type != TamaParkEncounterType.RECYCLER || encounter.phase != TamaParkEncounterPhase.CLEANUP) {
                    "recycler_phase_invalid"
                } else null
            TamaWorldActionReceiptKind.SELLER_ACCEPT,
            TamaWorldActionReceiptKind.SELLER_DECLINE ->
                if (encounter.type != TamaParkEncounterType.SELLER || encounter.phase != TamaParkEncounterPhase.INTRO) {
                    "seller_phase_invalid"
                } else null
            TamaWorldActionReceiptKind.SELLER_SALE,
            TamaWorldActionReceiptKind.SELLER_FINISH ->
                if (encounter.type != TamaParkEncounterType.SELLER || encounter.phase != TamaParkEncounterPhase.SELLER_MARKET) {
                    "seller_phase_invalid"
                } else null
            else -> "unsupported_park_action"
        }
    }

    private fun expectedNpcId(kind: String): String? = when (kind) {
        TamaWorldActionReceiptKind.RECYCLER_HELP,
        TamaWorldActionReceiptKind.RECYCLER_FINISH,
        TamaWorldActionReceiptKind.RECYCLER_DECLINE -> "recycler"
        TamaWorldActionReceiptKind.SELLER_ACCEPT,
        TamaWorldActionReceiptKind.SELLER_SALE,
        TamaWorldActionReceiptKind.SELLER_DECLINE,
        TamaWorldActionReceiptKind.SELLER_FINISH -> "seller"
        else -> null
    }
}
