package com.example.llamadroid.tama.world

import android.app.Application
import androidx.room.Room
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.world.core.*
import com.example.llamadroid.tama.world.memory.LivingWorldJournal
import com.example.llamadroid.tama.world.memory.AdventureMemoryPolicy
import com.example.llamadroid.tama.world.memory.AdventureMemoryPreferences
import com.example.llamadroid.tama.world.persistence.WorldBuildingLayouts
import com.example.llamadroid.tama.world.persistence.WorldInitializer
import com.example.llamadroid.tama.world.persistence.WorldStateStore
import com.example.llamadroid.tama.world.persistence.WorldTransfers
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptRequest
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import com.example.llamadroid.tama.world.policy.MovementIntent
import com.example.llamadroid.tama.world.training.CurriculumLevel
import com.example.llamadroid.tama.world.training.GridNavigationEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WorldPersistenceTest {
    private lateinit var database: TamaDatabase
    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TamaDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() { database.close() }

    @Test fun initializerPreservesSecondDungeonRelationshipsAndIsIdempotent() = runBlocking {
        val pet = TamaPet(id = "world-test-pet", name = "Pixel", currentLocationId = "fixed_4_2",
            relationships = mapOf("farm_farmer" to 42), discoveredLocationIds = setOf("home", "fixed_0_2", "fixed_4_2"))
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        val first = WorldInitializer(database).ensure(pet, 1000)
        assertEquals("fixed_4_2", first.actor.structureId)
        assertTrue(first.knownPlaces.any { it.id == "fixed_0_2" })
        assertTrue(first.knownPlaces.any { it.id == "fixed_4_2" })
        assertEquals(42f, database.worldDao().relationships(pet.id).single().friendship, 0f)
        val changed = first.copy(tick = 23, explored = first.explored.reversed(),
            actor = first.actor.copy(action = ActionId.TALK, actionState = ActionState.RUNNING, actionTicksRemaining = 9))
        WorldStateStore(database).save(changed, first)
        assertEquals(changed, WorldInitializer(database).ensure(pet, 99_999))
        val request = TamaWorldActionReceiptRequest("receipt-1", pet.id, first.worldId,
            TamaWorldActionReceiptKind.PARK_QUEST_FINISH, LegacyLocationAliases.PARK, "mint_fox", 1000L, questId = "q1")
        val receipt = TamaWorldActionReceiptEntity(pet.id, request.receiptId, first.worldId, request.kind,
            Json.encodeToString(request), status = TamaWorldActionReceiptStatus.SUCCEEDED,
            createdAt = 1000L, updatedAt = 2000L, completedAt = 2000L)
        database.worldActionReceiptDao().save(receipt)
        val transfer = WorldTransfers.export(database, pet.id)!!
        database.worldDao().clearPet(pet.id)
        WorldTransfers.restore(database, pet.id, transfer)
        assertEquals(changed, WorldStateStore(database).load(pet.id))
        assertEquals(receipt, database.worldActionReceiptDao().byId(pet.id, receipt.id))
        assertTrue(database.worldDao().events(pet.id).isEmpty())
    }

    @Test fun fiveThousandSyntheticTransitionsCannotCreateAdventureOrChatMemories() = runBlocking {
        val pet = TamaPet(id = "isolated-pet", name = "Pixel")
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        WorldInitializer(database).ensure(pet, 1000)
        val environment = GridNavigationEnvironment(0, fixedTargetForVisibleCurriculum = false)
        var episode = 0L
        environment.reset(episode, CurriculumLevel.FOOD_AND_WATER)
        repeat(5000) { index ->
            val step = environment.step(MovementIntent.entries[index % MovementIntent.entries.size])
            if (step.done) environment.reset(++episode, CurriculumLevel.FOOD_AND_WATER)
        }
        environment.close()
        assertTrue(database.worldDao().events(pet.id).isEmpty())
        assertTrue(database.worldDao().episodes(pet.id).isEmpty())
        assertTrue(database.tamaDao().getAllEvents(pet.id).isEmpty())
        assertTrue(database.tamaDao().getAllSummaries(pet.id).isEmpty())
        assertTrue(database.tamaDao().getChatHistory(pet.id).isEmpty())
        assertFalse(LivingWorldJournal(database).contextFacts(pet.id).contains("training", ignoreCase = true))
    }

    @Test fun everyLegacyFacilityInitializesWithoutLosingFrozenActivityProgress() = runBlocking {
        LegacyLocationAliases.allIds.forEach { location ->
            val pet = TamaPet(id = "legacy-$location", name = "Pixel", stage = GrowthStage.TEEN,
                currentLocationId = location, currentActivity = ActivityType.WORKING,
                activityStartTime = 1234L, cycleFrozen = true, cycleFreezeStartedAt = 5678L, money = 9876L)
            database.tamaDao().savePet(PetMapper.toEntity(pet))
            val world = WorldInitializer(database).ensure(pet, 10_000L)
            assertEquals(location, world.actor.structureId)
            assertEquals(location, world.knownPlaces.first { it.id == location }.id)
            val preserved = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
            assertEquals(pet.currentActivity, preserved.currentActivity)
            assertEquals(pet.activityStartTime, preserved.activityStartTime)
            assertEquals(pet.cycleFreezeStartedAt, preserved.cycleFreezeStartedAt)
            assertTrue(preserved.cycleFrozen)
            assertEquals(pet.money, preserved.money)
        }
    }

    @Test fun replayedMajorLivingEventKeepsOneEpisodeAndEvidenceLink() = runBlocking {
        val pet = TamaPet(id = "journal-pet", name = "Pixel")
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        val before = WorldInitializer(database).ensure(pet, 1000)
        val after = before.copy(tick = 1, lastSimulatedAt = 1100)
        val event = WorldEffectRequest.Event("quest_completed", EventImportance.MAJOR, pet.id, mapOf("questId" to "q1"))
        val journal = LivingWorldJournal(database)
        repeat(2) { journal.record(before, after, listOf(event)) }
        assertEquals(1, database.worldDao().events(pet.id).size)
        val episode = database.worldDao().episodes(pet.id).single()
        assertEquals("PENDING", episode.memoryStatus)
        assertEquals(database.worldDao().events(pet.id).map { it.id }, LivingWorldJournal.decodeEvidence(episode.evidenceJson))
    }

    @Test fun manualMemoryApprovalAndNpcHistoryRemainScopedToLivingPet() = runBlocking {
        val pet = TamaPet(id = "memory-choice-pet", name = "Pixel")
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        val before = WorldInitializer(database).ensure(pet, 1000)
        val journal = LivingWorldJournal(database)
        journal.record(before, before.copy(tick = 1, lastSimulatedAt = 1100), listOf(
            WorldEffectRequest.Event("quest_completed", EventImportance.MAJOR, pet.id, mapOf("npcId" to "mint_fox"))
        ))
        val preferences = AdventureMemoryPreferences(RuntimeEnvironment.getApplication())
        assertEquals(AdventureMemoryPolicy.AUTO_MAJOR, preferences.policy(pet.id))
        preferences.setPolicy(pet.id, AdventureMemoryPolicy.ASK)
        assertTrue(database.worldDao().readyMemories(pet.id, false).isEmpty())
        val episode = database.worldDao().episodes(pet.id).single()
        preferences.approve(database, pet.id, episode.id)
        assertEquals(episode.id, database.worldDao().readyMemories(pet.id, false).single().id)
        assertTrue(journal.contextFacts(pet.id, "What did Mint Fox do?", 2000).contains("quest_completed"))
        assertTrue(database.worldDao().queryAdventures("different-pet", 0, 2000).isEmpty())
    }

    @Test fun productionBuildingLayoutsKeepFacilitiesReachableAcrossOneHundredSeeds() {
        val layouts = WorldBuildingLayouts.read(RuntimeEnvironment.getApplication())
        assertEquals(5, layouts.getValue(StructureType.HOME).widthTiles)
        assertEquals(WorldCoordinate(2, 3), layouts.getValue(StructureType.HOME).entrance)
        repeat(100) { seed ->
            val world = WorldGenerator.generate(seed.toLong(), structureLayouts = layouts)
            val home = world.structures.first { it.type == StructureType.HOME }
            world.structures.filter { it.mandatory }.forEach { structure ->
                assertFalse("Door blocked for seed $seed at ${structure.id}", structure.blocks(structure.entrance))
                assertTrue("Unreachable facility for seed $seed: ${structure.id}",
                    structure.entrance == home.entrance ||
                        Pathfinder.findPath(world, home.entrance, structure.entrance).isNotEmpty())
            }
        }
    }
}
