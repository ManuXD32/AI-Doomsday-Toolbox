package com.example.llamadroid.tama.world.runtime

import com.example.llamadroid.tama.data.TamaParkEncounter
import com.example.llamadroid.tama.data.TamaParkEncounterPhase
import com.example.llamadroid.tama.data.TamaParkEncounterType
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import com.example.llamadroid.tama.world.core.NpcRole
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.StructureType
import com.example.llamadroid.tama.world.core.WorldActor
import com.example.llamadroid.tama.world.core.WorldCoordinate
import com.example.llamadroid.tama.world.core.WorldEffectRequest
import com.example.llamadroid.tama.world.core.WorldNpc
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.core.WorldStructure
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptDao
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ParkWorldActionExecutorTest {
    private val json = Json { encodeDefaults = true }
    private val now = Instant.parse("2026-09-10T08:30:00Z").toEpochMilli()

    @Test
    fun `recycler completion is exactly once and replay returns durable result`() = runBlocking {
        val request = TamaParkWorldActionRequests.recycler(
            "receipt-finish", "pet-1", "world-1",
            TamaWorldActionReceiptKind.RECYCLER_FINISH, now
        )
        val dao = FakeReceiptDao().also { it.put(queued(request)) }
        val port = FakeParkPort(cleanupPet())
        val executor = TamaParkWorldActionExecutor(dao, port, json)
        val effect = WorldEffectRequest.CanonicalAction(
            TamaParkWorldAction.CANONICAL_ACTION,
            TamaParkWorldAction.arguments(request)
        )

        val first = executor.completeInsideTransaction(world(), effect, now)
        assertTrue(first.domainAccepted)
        assertEquals(1, port.recyclerFinishCalls)
        assertEquals("200 coins", first.result.message)
        assertEquals(TamaWorldActionReceiptStatus.SUCCEEDED, dao.byId("pet-1", request.receiptId)!!.status)

        val replay = executor.completeInsideTransaction(world(), effect, now + 100)
        assertTrue(replay.domainAccepted)
        assertEquals(1, port.recyclerFinishCalls)
        assertEquals(first.result, replay.result)
    }

    @Test
    fun `stale encounter phase rejects before legacy reward method`() = runBlocking {
        val request = TamaParkWorldActionRequests.recycler(
            "receipt-stale", "pet-1", "world-1",
            TamaWorldActionReceiptKind.RECYCLER_FINISH, now
        )
        val dao = FakeReceiptDao().also { it.put(queued(request)) }
        val port = FakeParkPort(cleanupPet().copy(currentParkEncounter =
            cleanupPet().currentParkEncounter!!.copy(phase = TamaParkEncounterPhase.INTRO)))
        val executor = TamaParkWorldActionExecutor(dao, port, json)

        val result = executor.completeInsideTransaction(
            world(),
            WorldEffectRequest.CanonicalAction(
                TamaParkWorldAction.CANONICAL_ACTION,
                TamaParkWorldAction.arguments(request)
            ),
            now
        )

        assertFalse(result.domainAccepted)
        assertEquals("recycler_phase_invalid", result.result.errorCode)
        assertEquals(0, port.recyclerFinishCalls)
        assertEquals(TamaWorldActionReceiptStatus.REJECTED, dao.byId("pet-1", request.receiptId)!!.status)
    }

    private fun queued(request: com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptRequest) =
        TamaWorldActionReceiptEntity(
            petId = request.petId,
            id = request.receiptId,
            worldId = request.worldId,
            kind = request.kind,
            requestJson = json.encodeToString(request),
            createdAt = request.requestedAt,
            updatedAt = request.requestedAt
        )

    private fun world() = WorldState(
        seed = 1L,
        worldId = "world-1",
        petId = "pet-1",
        actor = WorldActor(
            actorId = "pet-1",
            x = 4,
            y = 4,
            presence = PresenceMode.WORLD,
            structureId = null
        ),
        structures = listOf(
            WorldStructure(
                id = LegacyLocationAliases.PARK,
                type = StructureType.PARK,
                x = 0,
                y = 0,
                // The fixture places the recycler at (5,4). Keep that live
                // coordinate inside the Park so completion reaches the
                // encounter-phase validation instead of failing the physical
                // counterparty-location guard first.
                width = 6,
                height = 5,
                entrance = WorldCoordinate(1, 1)
            )
        ),
        npcs = listOf(
            WorldNpc(
                id = "recycler",
                name = "Recycler",
                role = NpcRole.RECYCLER,
                x = 5,
                y = 4,
                jobStructureId = LegacyLocationAliases.PARK,
                scheduleState = "working"
            )
        ),
        explored = listOf(WorldCoordinate(4, 4), WorldCoordinate(5, 4)),
        lastSimulatedAt = now
    )

    private fun cleanupPet() = TamaPet(
        id = "pet-1",
        name = "Pixel",
        currentParkEncounter = TamaParkEncounter(
            npcId = "recycler",
            type = TamaParkEncounterType.RECYCLER,
            phase = TamaParkEncounterPhase.CLEANUP
        )
    )

    private class FakeParkPort(private val pet: TamaPet) : TamaParkWorldActionPort {
        var recyclerFinishCalls = 0

        override suspend fun refreshFromCanonicalStore() = Unit
        override fun currentPet() = pet
        override suspend fun questNpcIdLocked(questId: String): String? = null
        override suspend fun acceptQuestLocked(questId: String, now: Long) =
            TamaParkLegacyOutcome(false, "unused", "unused")
        override suspend fun finishQuestLocked(questId: String, now: Long) =
            TamaParkLegacyOutcome(false, "unused", "unused")
        override suspend fun acceptRecyclerLocked(now: Long, dateKey: String) =
            TamaParkLegacyOutcome(false, "unused", "unused")
        override suspend fun finishRecyclerLocked(): TamaParkLegacyOutcome {
            recyclerFinishCalls++
            return TamaParkLegacyOutcome(true, "200 coins")
        }
        override suspend fun declineRecyclerLocked(now: Long, dateKey: String) =
            TamaParkLegacyOutcome(false, "unused", "unused")
        override suspend fun acceptSellerLocked() =
            TamaParkLegacyOutcome(false, "unused", "unused")
        override suspend fun sellerSaleLocked(itemId: String, quantity: Int) =
            TamaParkLegacyOutcome(false, "unused", "unused")
        override suspend fun declineSellerLocked() =
            TamaParkLegacyOutcome(false, "unused", "unused")
        override suspend fun finishSellerLocked() =
            TamaParkLegacyOutcome(false, "unused", "unused")
    }

    private class FakeReceiptDao : TamaWorldActionReceiptDao() {
        private val rows = linkedMapOf<Pair<String, String>, TamaWorldActionReceiptEntity>()
        private val updates = MutableStateFlow<List<TamaWorldActionReceiptEntity>>(emptyList())

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
        override suspend fun recent(petId: String, limit: Int) = rows.values
            .filter { it.petId == petId }.take(limit)
        override suspend fun all(petId: String) = rows.values.filter { it.petId == petId }
        override fun observeUnacknowledged(petId: String): Flow<List<TamaWorldActionReceiptEntity>> = updates
        override suspend fun insertIfAbsent(value: TamaWorldActionReceiptEntity): Long {
            if (rows.containsKey(value.petId to value.id)) return -1L
            rows[value.petId to value.id] = value
            publish()
            return rows.size.toLong()
        }
        override suspend fun save(value: TamaWorldActionReceiptEntity) {
            rows[value.petId to value.id] = value
            publish()
        }
        override suspend fun saveAll(values: List<TamaWorldActionReceiptEntity>) {
            values.forEach { save(it) }
        }
        override suspend fun completeActive(
            petId: String, id: String, status: String, updatedAt: Long,
            completedAt: Long?, resultJson: String?
        ): Int {
            val key = petId to id
            val current = rows[key] ?: return 0
            if (current.status !in TamaWorldActionReceiptStatus.active) return 0
            rows[key] = current.copy(
                status = status, updatedAt = updatedAt,
                completedAt = completedAt, resultJson = resultJson
            )
            publish()
            return 1
        }
        override suspend fun markRunning(petId: String, id: String, updatedAt: Long): Int {
            val key = petId to id
            val current = rows[key] ?: return 0
            if (current.status != TamaWorldActionReceiptStatus.QUEUED) return 0
            rows[key] = current.copy(
                status = TamaWorldActionReceiptStatus.RUNNING,
                updatedAt = updatedAt
            )
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
        override suspend fun acknowledge(petId: String, id: String, acknowledgedAt: Long): Int = 0
        override suspend fun pruneAcknowledged(petId: String, before: Long): Int = 0
        override suspend fun clearPet(petId: String) {
            rows.keys.filter { it.first == petId }.toList().forEach(rows::remove)
            publish()
        }

        private fun publish() { updates.value = rows.values.toList() }
    }
}
