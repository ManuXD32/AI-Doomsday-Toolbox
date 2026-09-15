package com.example.llamadroid.tama.world.runtime

import android.app.Application
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.QuestItemRequest
import com.example.llamadroid.tama.data.TamaLocalizedText
import com.example.llamadroid.tama.data.TamaParkEncounter
import com.example.llamadroid.tama.data.TamaParkEncounterPhase
import com.example.llamadroid.tama.data.TamaParkEncounterType
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TamaQuestStatus
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.db.TamaQuestEntity
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.ActionState
import com.example.llamadroid.tama.world.core.AutonomyLevel
import com.example.llamadroid.tama.world.core.AutonomyPolicy
import com.example.llamadroid.tama.world.core.DEFAULT_TICK_MILLIS
import com.example.llamadroid.tama.world.core.GoalId
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.StructureType
import com.example.llamadroid.tama.world.core.WorldCommand
import com.example.llamadroid.tama.world.core.WorldCoordinate
import com.example.llamadroid.tama.world.core.WorldGenerator
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptResult
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import com.example.llamadroid.tama.world.persistence.WorldStateStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WorldParkActionIntegrationTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private lateinit var database: TamaDatabase
    private lateinit var farm: FarmRepository
    private lateinit var engine: TamaGameEngine

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        farm = spyk(FarmRepository(database.farmDao(), context))
        mockkObject(TamaNotificationScheduler)
        coEvery { TamaNotificationScheduler.scheduleForPet(any(), any()) } just Runs
        engine = TamaGameEngine(
            context,
            database.tamaDao(),
            FarmEngine(farm),
            farm,
            mockk<SettingsRepository>(relaxed = true),
            database,
            observeAndTick = false
        )
    }

    @After
    fun tearDown() = runBlocking {
        engine.close().join()
        database.close()
        unmockkAll()
    }

    @Test
    fun questReceiptsCompletePhysicallyAndReplayDurablePresentation() = runBlocking {
        val pet = TamaPet(
            id = "park-quest-integration-pet",
            name = "Pixel",
            stage = GrowthStage.TEEN,
            money = 17L
        )
        start(pet)
        val clock = nextThursdayAtNineUtc()
        val positioned = placeActorAtNpc("cloud_bunny", StructureType.PARK, clock)
        val questId = installQuest(pet.id, "cloud_bunny", clock, rewardCoins = 37L)
        val accept = TamaParkWorldActionRequests.questAccept(
            receiptId = "quest-accept-1",
            petId = pet.id,
            worldId = positioned.worldId,
            questId = questId,
            npcId = "cloud_bunny",
            requestedAt = clock
        )

        val admitted = engine.world.queueParkAction(accept)
        assertTrue(admitted.accepted)
        assertEquals(TamaWorldActionReceiptStatus.QUEUED, admitted.status)
        val accepted = advanceUntilTerminal(accept.receiptId)
        assertEquals(TamaWorldActionReceiptStatus.SUCCEEDED, accepted.status)
        assertEquals(TamaQuestStatus.ACCEPTED.name, questRow(questId).status)
        assertEquals(pet.money, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).money)

        reloadWorld()
        val acceptReplay = engine.world.queueParkAction(accept)
        assertTrue(acceptReplay.accepted)
        assertEquals(TamaWorldActionReceiptStatus.SUCCEEDED, acceptReplay.status)
        assertEquals(1, database.worldActionReceiptDao().all(pet.id).size)

        val acceptedPet = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        database.tamaDao().savePet(PetMapper.toEntity(acceptedPet.copy(
            inventory = listOf(InventoryItem("crop_carrot", "Carrot", ItemType.CROP, quantity = 1))
        )))
        engine.reloadPersistedPet()
        val finishState = requireNotNull(engine.world.state.value)
        val finish = TamaParkWorldActionRequests.questFinish(
            receiptId = "quest-finish-1",
            petId = pet.id,
            worldId = finishState.worldId,
            questId = questId,
            npcId = "cloud_bunny",
            requestedAt = clock
        )
        val finishAdmitted = engine.world.queueParkAction(finish)
        assertTrue(finishAdmitted.accepted)
        val completed = advanceUntilTerminal(finish.receiptId)
        assertEquals(TamaWorldActionReceiptStatus.SUCCEEDED, completed.status)
        val result = json.decodeFromString<TamaWorldActionReceiptResult>(completed.resultJson!!)
        assertTrue(result.success)
        assertNotNull(result.questPresentation)
        assertEquals(37L, result.questPresentation!!.rewardCoins)
        assertEquals(TamaQuestStatus.COMPLETED.name, questRow(questId).status)
        val rewarded = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertEquals(pet.money + 37L, rewarded.money)
        assertTrue(rewarded.inventory.none { it.id == "crop_carrot" })

        reloadWorld()
        val finishReplay = engine.world.queueParkAction(finish)
        assertTrue(finishReplay.accepted)
        assertEquals(TamaWorldActionReceiptStatus.SUCCEEDED, finishReplay.status)
        assertEquals(pet.money + 37L, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).money)
        assertEquals(2, database.worldActionReceiptDao().all(pet.id).size)
    }

    @Test
    fun sellerReceiptUsesLockedSaleAndReplayDoesNotRequeueOrPayAgain() = runBlocking {
        val startingMoney = 23L
        val pet = TamaPet(
            id = "seller-integration-pet",
            name = "Pixel",
            stage = GrowthStage.TEEN,
            money = startingMoney,
            inventory = listOf(InventoryItem("crop_carrot", "Carrot", ItemType.CROP, quantity = 3)),
            currentParkEncounter = TamaParkEncounter(
                npcId = "seller",
                type = TamaParkEncounterType.SELLER,
                phase = TamaParkEncounterPhase.SELLER_MARKET
            )
        )
        start(pet)
        val positioned = placeActorAtNpc("seller", StructureType.MARKET_STALL, nextThursdayAtNineUtc())
        val quote = engine.getParkMarketBoard().quotes.first { it.itemId == "crop_carrot" }
        val request = TamaParkWorldActionRequests.sellerSale(
            receiptId = "seller-sale-1",
            petId = pet.id,
            worldId = positioned.worldId,
            itemId = "crop_carrot",
            quantity = 2,
            requestedAt = positioned.lastSimulatedAt
        )

        val admitted = engine.world.queueParkAction(request)
        assertTrue(admitted.accepted)
        val completed = advanceUntilTerminal(request.receiptId)
        assertEquals(TamaWorldActionReceiptStatus.SUCCEEDED, completed.status)
        val soldPet = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertEquals(startingMoney + quote.currentPrice * 2L, soldPet.money)
        assertEquals(1, soldPet.inventory.single { it.id == "crop_carrot" }.quantity)
        assertEquals(1, database.worldActionReceiptDao().all(pet.id).size)

        reloadWorld()
        val replay = engine.world.queueParkAction(request)
        assertTrue(replay.accepted)
        assertEquals(TamaWorldActionReceiptStatus.SUCCEEDED, replay.status)
        assertEquals(startingMoney + quote.currentPrice * 2L,
            PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).money)
        assertEquals(1, database.worldActionReceiptDao().all(pet.id).size)
    }

    @Test
    fun stoppingQueuedParkActionTerminalizesReceiptWithoutChangingQuest() = runBlocking {
        val pet = TamaPet(id = "park-stop-integration-pet", name = "Pixel", stage = GrowthStage.TEEN)
        start(pet)
        val clock = nextThursdayAtNineUtc()
        val positioned = placeActorAtNpc("cloud_bunny", StructureType.PARK, clock)
        val questId = installQuest(pet.id, "cloud_bunny", clock, rewardCoins = 31L)
        val request = TamaParkWorldActionRequests.questAccept(
            "quest-stop-1", pet.id, positioned.worldId, questId, "cloud_bunny", clock
        )
        assertTrue(engine.world.queueParkAction(request).accepted)
        assertEquals(TamaWorldActionReceiptStatus.QUEUED,
            database.worldActionReceiptDao().byId(pet.id, request.receiptId)!!.status)

        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        val stopped = database.worldActionReceiptDao().byId(pet.id, request.receiptId)!!
        assertEquals(TamaWorldActionReceiptStatus.REJECTED, stopped.status)
        val stopResult = json.decodeFromString<TamaWorldActionReceiptResult>(stopped.resultJson!!)
        assertTrue(stopResult.errorCode == "world_action_cancelled" ||
            stopResult.errorCode == "world_action_replaced")
        assertEquals(TamaQuestStatus.AVAILABLE.name, questRow(questId).status)
        assertEquals(pet.money, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).money)

        val replay = engine.world.queueParkAction(request)
        assertTrue(replay.accepted)
        assertEquals(TamaWorldActionReceiptStatus.REJECTED, replay.status)
        assertEquals(1, database.worldActionReceiptDao().all(pet.id).size)
    }

    @Test
    fun queueRollbackRecoveryReloadsPersistedWorldAndCanonicalPet() = runBlocking {
        val pet = TamaPet(id = "park-recovery-integration-pet", name = "Pixel", stage = GrowthStage.TEEN)
        start(pet)
        val state = placeActorAtNpc("cloud_bunny", StructureType.PARK, nextThursdayAtNineUtc())
        val moved = listOf(
            WorldCoordinate(state.actor.x + 2, state.actor.y),
            WorldCoordinate(state.actor.x - 2, state.actor.y),
            WorldCoordinate(state.actor.x, state.actor.y + 2),
            WorldCoordinate(state.actor.x, state.actor.y - 2)
        ).first { state.contains(it) }
        val persisted = state.copy(actor = state.actor.copy(
            x = moved.x,
            y = moved.y,
            preciseX = moved.x.toDouble(),
            preciseY = moved.y.toDouble(),
            goal = GoalId.IDLE,
            action = ActionId.WAIT,
            actionState = ActionState.IDLE,
            actionTicksRemaining = 0,
            path = emptyList(),
            actionArguments = emptyMap(),
            pendingCommand = null
        ))
        WorldStateStore(database).save(persisted, state)
        assertNotEquals(moved, requireNotNull(engine.world.state.value).actor.coordinate)

        // This is the same recovery hook used when the outer receipt/intent
        // transaction rolls back after the controller has published a state.
        engine.world.recoverAfterParkQueueFailure()
        assertEquals(persisted, WorldStateStore(database).load(pet.id))
        assertEquals(moved, requireNotNull(engine.world.state.value).actor.coordinate)
        assertEquals(pet.money, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).money)
    }

    private suspend fun start(pet: TamaPet) {
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        engine.reloadPersistedPet()
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        engine.world.setAutonomy(AutonomyPolicy(level = AutonomyLevel.OFF))
    }

    private suspend fun placeActorAtNpc(
        npcId: String,
        structureType: StructureType,
        clock: Long
    ): WorldState {
        val current = requireNotNull(engine.world.state.value)
        val structure = current.structures.first { it.type == structureType }
        val npcCoordinate = structure.entrance
        val candidates = listOf(
            WorldCoordinate(npcCoordinate.x - 1, npcCoordinate.y),
            WorldCoordinate(npcCoordinate.x + 1, npcCoordinate.y),
            WorldCoordinate(npcCoordinate.x, npcCoordinate.y - 1),
            WorldCoordinate(npcCoordinate.x, npcCoordinate.y + 1),
            WorldCoordinate(npcCoordinate.x - 1, npcCoordinate.y - 1),
            WorldCoordinate(npcCoordinate.x + 1, npcCoordinate.y + 1)
        ).filter(current::contains)
        val actorCoordinate = candidates.firstOrNull {
            WorldGenerator.tileAt(current, it.x, it.y).walkable
        } ?: candidates.first()
        val actor = current.actor.copy(
            actorId = current.petId,
            x = actorCoordinate.x,
            y = actorCoordinate.y,
            preciseX = actorCoordinate.x.toDouble(),
            preciseY = actorCoordinate.y.toDouble(),
            presence = PresenceMode.WORLD,
            structureId = null,
            goal = GoalId.IDLE,
            action = ActionId.WAIT,
            actionState = ActionState.IDLE,
            actionTicksRemaining = 0,
            destinationX = null,
            destinationY = null,
            pendingStructureId = null,
            actionTargetId = null,
            actionTargetX = null,
            actionTargetY = null,
            path = emptyList(),
            actionArguments = emptyMap(),
            pendingCommand = null,
            pendingActivity = null
        )
        val npcs = current.npcs.map { npc ->
            if (npc.id != npcId) npc.copy(lastSimulatedAt = clock) else npc.copy(
                x = npcCoordinate.x,
                y = npcCoordinate.y,
                preciseX = npcCoordinate.x.toDouble(),
                preciseY = npcCoordinate.y.toDouble(),
                currentGoal = GoalId.IDLE,
                currentAction = ActionId.WAIT,
                actionState = ActionState.IDLE,
                scheduleState = "social",
                destinationX = null,
                destinationY = null,
                path = emptyList(),
                stationary = true,
                execution = null,
                lastSimulatedAt = clock
            )
        }
        val next = current.copy(
            actor = actor,
            npcs = npcs,
            explored = (current.explored + candidates + npcCoordinate).distinct(),
            lastSimulatedAt = clock,
            timezoneOffsetMinutes = 0
        )
        WorldStateStore(database).save(next, current)
        engine.world.invalidate()
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        return requireNotNull(engine.world.state.value)
    }

    private suspend fun installQuest(
        petId: String,
        npcId: String,
        now: Long,
        rewardCoins: Long
    ): String {
        val id = "integration-quest-$petId"
        val requests = listOf(QuestItemRequest("crop_carrot", 1))
        database.tamaDao().saveQuest(TamaQuestEntity(
            id = id,
            petId = petId,
            status = TamaQuestStatus.AVAILABLE.name,
            generatedDateKey = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate().toString(),
            acceptedAt = null,
            expiresAt = null,
            completedAt = null,
            npcId = npcId,
            requestsJson = json.encodeToString(requests),
            rewardCoins = rewardCoins,
            summaryJson = json.encodeToString(TamaLocalizedText(
                en = "Deliver one carrot",
                es = "Entrega una zanahoria"
            ))
        ))
        return id
    }

    private suspend fun advanceUntilTerminal(receiptId: String): TamaWorldActionReceiptEntity {
        repeat(40) {
            val row = database.worldActionReceiptDao().byId(
                requireNotNull(engine.world.state.value).petId,
                receiptId
            ) ?: error("receipt_missing:$receiptId")
            if (row.status in TamaWorldActionReceiptStatus.terminal) return row
            val state = requireNotNull(engine.world.state.value)
            val pet = PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(state.petId)))
            engine.world.advance(pet, state.lastSimulatedAt + DEFAULT_TICK_MILLIS)
        }
        return requireNotNull(database.worldActionReceiptDao().byId(
            requireNotNull(engine.world.state.value).petId,
            receiptId
        )) { "receipt_not_terminal:$receiptId" }
    }

    private suspend fun reloadWorld(): WorldState {
        engine.world.invalidate()
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        return requireNotNull(engine.world.state.value)
    }

    private suspend fun questRow(id: String): TamaQuestEntity =
        database.tamaDao().getQuestsForPet(requireNotNull(engine.pet.value).id).single { it.id == id }

    private fun nextThursdayAtNineUtc(): Long {
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atOffset(ZoneOffset.UTC).toLocalDate()
        return today.with(TemporalAdjusters.next(DayOfWeek.THURSDAY))
            .atTime(9, 0)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
    }
}
