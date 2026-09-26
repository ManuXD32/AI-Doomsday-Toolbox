package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.data.TamaParkEncounterPhase
import com.example.llamadroid.tama.data.TamaParkEncounterType
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import com.example.llamadroid.tama.world.core.NpcRole
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.WorldActor
import com.example.llamadroid.tama.world.core.WorldCoordinate
import com.example.llamadroid.tama.world.core.WorldNpc
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.core.WorldStructure
import com.example.llamadroid.tama.world.core.StructureType
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptDao
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptRequest
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptResult
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ParkWorldActionReceiptContractTest {
    @Test fun actionMappingUsesPhysicalCounterpartyActions() {
        assertEquals(ActionId.TALK, TamaParkWorldAction.coreAction(TamaWorldActionReceiptKind.PARK_QUEST_ACCEPT))
        assertEquals(ActionId.TALK, TamaParkWorldAction.coreAction(TamaWorldActionReceiptKind.PARK_QUEST_FINISH))
        assertEquals(ActionId.HELP_NPC, TamaParkWorldAction.coreAction(TamaWorldActionReceiptKind.RECYCLER_HELP))
        assertEquals(ActionId.HELP_NPC, TamaParkWorldAction.coreAction(TamaWorldActionReceiptKind.RECYCLER_FINISH))
        assertEquals(ActionId.SAY_GOODBYE, TamaParkWorldAction.coreAction(TamaWorldActionReceiptKind.SELLER_DECLINE))
        assertEquals(ActionId.TRADE, TamaParkWorldAction.coreAction(TamaWorldActionReceiptKind.SELLER_SALE))
        assertEquals(LegacyLocationAliases.PARK,
            TamaParkWorldAction.destinationId(TamaWorldActionReceiptKind.RECYCLER_HELP))
        assertEquals("market_stall",
            TamaParkWorldAction.destinationId(TamaWorldActionReceiptKind.SELLER_SALE))
    }

    @Test fun admissionIsIdempotentAndRejectsChangedRequest() = runBlocking {
        val store = TamaParkWorldActionReceiptStore(FakeReceiptDao())
        val firstRequest = TamaParkWorldActionRequests.sellerSale(
            receiptId = "receipt-1", petId = "pet-1", worldId = "world-1",
            itemId = "crop_carrot", quantity = 1, requestedAt = 100L
        )
        val first = store.admit(firstRequest, now = 100L)
        assertEquals(TamaParkWorldActionReceiptStore.Decision.CREATED, first.decision)
        val duplicate = store.admit(firstRequest, now = 200L)
        assertEquals(TamaParkWorldActionReceiptStore.Decision.EXISTING_ACTIVE, duplicate.decision)
        assertEquals(100L, duplicate.receipt.createdAt)
        val changed = store.admit(firstRequest.copy(quantity = 2), now = 200L)
        assertEquals(TamaParkWorldActionReceiptStore.Decision.CONFLICT, changed.decision)
        assertEquals("receipt_request_conflict", changed.reason)

        // SQLite returns the inserted rowid, not a constant 1. A second
        // successful insert must still be classified as CREATED.
        val second = store.admit(
            firstRequest.copy(receiptId = "receipt-2", itemId = "crop_turnip"),
            now = 200L
        )
        assertEquals(TamaParkWorldActionReceiptStore.Decision.CREATED, second.decision)
    }

    @Test fun recyclerRequiresWorkingWindowAndAdjacentLiveNpc() {
        val now = Instant.parse("2026-09-10T08:30:00Z").toEpochMilli() // Thursday, day 10
        val request = TamaParkWorldActionRequests.recycler(
            "receipt-2", "pet-1", "world-1", TamaWorldActionReceiptKind.RECYCLER_HELP, now
        )
        val valid = recyclerState(WorldCoordinate(2, 1), "working")
        assertNull(TamaParkWorldAction.validateWorld(valid, request, now))
        assertEquals("counterparty_not_adjacent", TamaParkWorldAction.validateWorld(
            valid.copy(actor = valid.actor.copy(x = 20, y = 20)), request, now
        ))
        assertEquals("recycler_out_of_window", TamaParkWorldAction.validateWorld(valid, request, 0L))
        // The persisted schedule label can lag a movement/social transition;
        // the calendar window and live position are the completion evidence.
        assertNull(TamaParkWorldAction.validateWorld(
            valid.copy(npcs = valid.npcs.map { it.copy(scheduleState = "home") }), request, now
        ))
        assertEquals("counterparty_not_at_park", TamaParkWorldAction.validateWorld(
            valid.copy(
                actor = valid.actor.copy(x = 8, y = 8),
                npcs = valid.npcs.map { it.copy(x = 8, y = 9, scheduleState = "working") }
            ), request, now
        ))
        assertEquals("wrong_counterparty", TamaParkWorldAction.validateWorld(
            valid.copy(npcs = valid.npcs.map { it.copy(role = NpcRole.PARK_RESIDENT) }), request, now
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sellerSaleRequiresPositiveSelectedQuantity() {
        TamaParkWorldActionRequests.sellerSale(
            "receipt-invalid", "pet-1", "world-1", "crop_carrot", 0, 1L
        )
    }

    @Test
    fun questUsesLiveNpcAdjacencyWithoutStaleParkLocationRequirement() {
        val now = Instant.parse("2026-09-10T08:30:00Z").toEpochMilli()
        val request = TamaParkWorldActionRequests.questAccept(
            "receipt-quest", "pet-1", "world-1", "quest-1", "paper_pup", now
        )
        val state = recyclerState(WorldCoordinate(2, 1), "working").copy(
            npcs = listOf(WorldNpc(
                id = "paper_pup",
                name = "Paper Pup",
                role = NpcRole.WORKPLACE_RESIDENT,
                x = 2,
                y = 1,
                jobStructureId = LegacyLocationAliases.WORKPLACE,
                scheduleState = "working"
            ))
        )
        assertNull(TamaParkWorldAction.validateWorld(state, request, now))
    }

    @Test
    fun staleUnreferencedReceiptIsTerminalizedAfterBoundedRecoveryWindow() = runBlocking {
        val now = 10_000L
        val request = TamaParkWorldActionRequests.recycler(
            "receipt-orphan", "pet-1", "world-1",
            TamaWorldActionReceiptKind.RECYCLER_HELP, now
        )
        val dao = FakeReceiptDao()
        dao.put(TamaWorldActionReceiptEntity(
            petId = request.petId,
            id = request.receiptId,
            worldId = request.worldId,
            kind = request.kind,
            requestJson = kotlinx.serialization.json.Json.encodeToString(request),
            createdAt = 1L,
            updatedAt = now - TamaParkWorldActionReceiptRecovery.ORPHAN_TIMEOUT_MS
        ))
        val store = TamaParkWorldActionReceiptStore(dao)
        val rejected = TamaParkWorldActionReceiptRecovery.rejectOrphans(
            recyclerState(WorldCoordinate(2, 1), "working"), store, now
        )
        assertEquals(1, rejected)
        val result = kotlinx.serialization.json.Json.decodeFromString<TamaWorldActionReceiptResult>(
            dao.byId("pet-1", request.receiptId)!!.resultJson!!
        )
        assertEquals("world_action_orphaned", result.errorCode)
        assertEquals(TamaWorldActionReceiptStatus.REJECTED,
            dao.byId("pet-1", request.receiptId)!!.status)
    }

    @Test
    fun recoveryLeavesForeignArcadeLeaseReceiptsUntouched() = runBlocking {
        val now = 10_000L
        val dao = FakeReceiptDao()
        dao.put(TamaWorldActionReceiptEntity(
            petId = "pet-1",
            id = "arcade-lease-1",
            worldId = "world-1",
            kind = "ARCADE_LEASE",
            requestJson = "{}",
            createdAt = 1L,
            updatedAt = now - TamaParkWorldActionReceiptRecovery.ORPHAN_TIMEOUT_MS
        ))
        val rejected = TamaParkWorldActionReceiptRecovery.rejectOrphans(
            recyclerState(WorldCoordinate(2, 1), "home"),
            TamaParkWorldActionReceiptStore(dao),
            now
        )
        assertEquals(0, rejected)
        assertEquals(TamaWorldActionReceiptStatus.QUEUED,
            dao.byId("pet-1", "arcade-lease-1")!!.status)
    }

    @Test fun encounterPhaseMustMatchEachSpecialReceipt() {
        val intro = TamaPet(id = "pet-1", name = "Pixel", currentParkEncounter =
            com.example.llamadroid.tama.data.TamaParkEncounter(
                npcId = "recycler", type = TamaParkEncounterType.RECYCLER,
                phase = TamaParkEncounterPhase.INTRO
            ))
        val finish = TamaParkWorldActionRequests.recycler(
            "receipt-3", "pet-1", "world-1", TamaWorldActionReceiptKind.RECYCLER_FINISH, 1L
        )
        assertEquals("recycler_phase_invalid", TamaParkWorldAction.validateEncounter(intro, finish))
        val cleanup = intro.copy(currentParkEncounter = intro.currentParkEncounter!!.copy(
            phase = TamaParkEncounterPhase.CLEANUP
        ))
        assertNull(TamaParkWorldAction.validateEncounter(cleanup, finish))
        val sellerSale = TamaParkWorldActionRequests.sellerSale(
            "receipt-4", "pet-1", "world-1", "crop_carrot", 1, 1L
        )
        // Match the seller counterparty first; otherwise the validator
        // correctly reports encounter_missing before it can inspect phase.
        val sellerWrongPhase = intro.copy(currentParkEncounter =
            intro.currentParkEncounter!!.copy(
                npcId = "seller",
                type = TamaParkEncounterType.SELLER,
                phase = TamaParkEncounterPhase.INTRO
            ))
        assertEquals("seller_phase_invalid", TamaParkWorldAction.validateEncounter(sellerWrongPhase, sellerSale))
    }

    private fun recyclerState(npcPosition: WorldCoordinate, schedule: String): WorldState {
        val park = WorldStructure(LegacyLocationAliases.PARK, StructureType.PARK, 0, 0,
            width = 5, height = 5, entrance = WorldCoordinate(1, 1))
        val market = WorldStructure("market_stall", StructureType.MARKET_STALL, 8, 0,
            width = 3, height = 2, entrance = WorldCoordinate(9, 1))
        return WorldState(
            seed = 1L,
            actor = WorldActor(actorId = "pet-1", x = 1, y = 1,
                preciseX = 1.0, preciseY = 1.0, presence = PresenceMode.WORLD,
                structureId = null),
            structures = listOf(park, market),
            npcs = listOf(WorldNpc("recycler", "The Recycler", NpcRole.RECYCLER,
                npcPosition.x, npcPosition.y, jobStructureId = LegacyLocationAliases.PARK,
                scheduleState = schedule)),
            explored = listOf(WorldCoordinate(1, 1), npcPosition),
            worldId = "world-1",
            petId = "pet-1"
        )
    }

    private class FakeReceiptDao : TamaWorldActionReceiptDao() {
        private val rows = LinkedHashMap<Pair<String, String>, TamaWorldActionReceiptEntity>()
        private val changes = MutableStateFlow<List<TamaWorldActionReceiptEntity>>(emptyList())

        fun put(value: TamaWorldActionReceiptEntity) {
            rows[value.petId to value.id] = value
            publish()
        }

        override suspend fun byId(petId: String, id: String) = rows[petId to id]
        override suspend fun active(petId: String) = rows.values.filter {
            it.petId == petId && it.status in TamaWorldActionReceiptStatus.active
        }
        override suspend fun unacknowledged(petId: String) = rows.values.filter {
            it.petId == petId && it.status in setOf(
                TamaWorldActionReceiptStatus.SUCCEEDED,
                TamaWorldActionReceiptStatus.REJECTED,
                TamaWorldActionReceiptStatus.FAILED
            )
        }
        override suspend fun recent(petId: String, limit: Int) = rows.values.filter { it.petId == petId }.take(limit)
        override suspend fun all(petId: String) = rows.values.filter { it.petId == petId }
        override fun observeUnacknowledged(petId: String): Flow<List<TamaWorldActionReceiptEntity>> = changes
        override suspend fun insertIfAbsent(value: TamaWorldActionReceiptEntity): Long {
            val key = value.petId to value.id
            if (rows.containsKey(key)) return -1L
            rows[key] = value
            publish()
            return rows.size.toLong()
        }
        override suspend fun save(value: TamaWorldActionReceiptEntity) {
            rows[value.petId to value.id] = value
            publish()
        }
        override suspend fun saveAll(values: List<TamaWorldActionReceiptEntity>) { values.forEach { save(it) } }
        override suspend fun completeActive(
            petId: String, id: String, status: String, updatedAt: Long,
            completedAt: Long?, resultJson: String?
        ): Int {
            val key = petId to id
            val current = rows[key] ?: return 0
            if (current.status !in TamaWorldActionReceiptStatus.active) return 0
            rows[key] = current.copy(status = status, updatedAt = updatedAt,
                completedAt = completedAt, resultJson = resultJson)
            publish()
            return 1
        }
        override suspend fun markRunning(petId: String, id: String, updatedAt: Long): Int {
            val key = petId to id
            val current = rows[key] ?: return 0
            if (current.status != TamaWorldActionReceiptStatus.QUEUED) return 0
            rows[key] = current.copy(status = TamaWorldActionReceiptStatus.RUNNING, updatedAt = updatedAt)
            publish()
            return 1
        }
        override suspend fun updateActiveRequest(petId: String, id: String, requestJson: String, updatedAt: Long): Int {
            val key = petId to id
            val current = rows[key] ?: return 0
            if (current.status !in TamaWorldActionReceiptStatus.active) return 0
            rows[key] = current.copy(requestJson = requestJson, updatedAt = updatedAt)
            publish()
            return 1
        }
        override suspend fun acknowledge(petId: String, id: String, acknowledgedAt: Long): Int =
            if (completeAcknowledgement(petId, id, acknowledgedAt)) 1 else 0
        override suspend fun pruneAcknowledged(petId: String, before: Long): Int = 0
        override suspend fun clearPet(petId: String) {
            rows.keys.filter { it.first == petId }.toList().forEach(rows::remove)
            publish()
        }

        private fun completeAcknowledgement(petId: String, id: String, at: Long): Boolean {
            val key = petId to id
            val current = rows[key] ?: return false
            if (current.status !in setOf(
                    TamaWorldActionReceiptStatus.SUCCEEDED,
                    TamaWorldActionReceiptStatus.REJECTED,
                    TamaWorldActionReceiptStatus.FAILED
                )) return false
            rows[key] = current.copy(status = TamaWorldActionReceiptStatus.ACKNOWLEDGED,
                acknowledgedAt = at, updatedAt = at)
            publish()
            return true
        }

        private fun publish() { changes.value = rows.values.toList() }
    }
}
