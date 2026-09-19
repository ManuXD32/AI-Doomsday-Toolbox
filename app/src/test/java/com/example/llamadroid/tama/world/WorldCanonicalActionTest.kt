package com.example.llamadroid.tama.world

import android.app.Application
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.WorldFarmMaintenance
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.tama.world.core.*
import com.example.llamadroid.tama.world.persistence.WorldStateStore
import com.example.llamadroid.tama.world.policy.RecurrentPpoPolicy
import com.example.llamadroid.tama.world.training.BASELINE_LIVING_POLICY_ID
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
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
class WorldCanonicalActionTest {
    private lateinit var database: TamaDatabase
    private lateinit var farm: FarmRepository
    private lateinit var engine: TamaGameEngine

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java).allowMainThreadQueries().build()
        farm = spyk(FarmRepository(database.farmDao(), context))
        mockkObject(TamaNotificationScheduler)
        coEvery { TamaNotificationScheduler.scheduleForPet(any(), any()) } just Runs
        engine = TamaGameEngine(context, database.tamaDao(), FarmEngine(farm), farm,
            mockk<SettingsRepository>(relaxed = true), database, observeAndTick = false)
    }

    @After fun tearDown() = runBlocking {
        engine.close().join()
        database.close()
        unmockkAll()
    }

    private suspend fun start(pet: TamaPet) {
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        engine.reloadPersistedPet()
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        engine.world.setAutonomy(AutonomyPolicy(level = AutonomyLevel.OFF))
        // Vendor hours are production behavior; these commerce fixtures always
        // start at local noon, independently of the workstation's clock.
        val current = requireNotNull(engine.world.state.value)
        WorldStateStore(database).save(current.copy(
            timezoneOffsetMinutes = 12 * 60 - WorldClock.at(current.lastSimulatedAt).minuteOfDay
        ), current)
        engine.world.invalidate()
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        // These fixtures exercise the optional physical-world action contract.
        assertTrue(engine.enterSimulatedWorld().success)
    }

    private suspend fun advance() {
        val state = requireNotNull(engine.world.state.value)
        val pet = PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(state.petId)))
        engine.world.advance(pet, state.lastSimulatedAt + 20_000L)
    }

    @Test fun barnShortcutQueuesPhysicalTravelAndKeepsCanonicalLocationUntilArrival() = runBlocking {
        val pet = TamaPet(id = "barn-pet", name = "Pixel", stage = GrowthStage.BABY)
        start(pet)
        val home = engine.world.state.value!!.actor.coordinate
        assertTrue(engine.travelToId("farm_barn").success)
        val traveling = engine.world.state.value!!.actor
        assertEquals(PresenceMode.WORLD, traveling.presence)
        assertEquals("farm_barn", traveling.pendingStructureId)
        assertTrue(traveling.coordinate.chebyshevDistanceTo(home) <= 1)
        assertNotEquals("farm_barn", database.tamaDao().getPet(pet.id)!!.currentLocationId)
    }

    @Test fun legacyFeedCompletesOnceAfterItsSavedDuration() = runBlocking {
        val pet = TamaPet(id = "care-pet", name = "Pixel", stage = GrowthStage.BABY,
            stats = PetStats(hunger = 40f), ownerBondLevel = 50f)
        start(pet)
        assertTrue(engine.feed().success)
        assertEquals(40f, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).stats.hunger, 0f)
        advance()
        val completed = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertEquals(70f, completed.stats.hunger, 0f)
        assertEquals(51f, completed.ownerBondLevel, 0f)
        advance()
        assertEquals(51f, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).ownerBondLevel, 0f)
    }

    @Test fun hospitalAndAlchemistPurchasesTravelToTheirOwnVendorBeforeCharging() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val pet = TamaPet(id = "vendor-pet", name = "Pixel", stage = GrowthStage.BABY, money = 20_000L)
        start(pet)
        listOf(LegacyLocationAliases.HOSPITAL to TamaPotionCatalog.HEAL_20_ID,
            LegacyLocationAliases.ALCHEMIST to TamaPotionCatalog.CHILD_STAGE_ID).forEach { (vendor, itemId) ->
            val potion = requireNotNull(TamaPotionCatalog.byId(itemId))
            val before = database.tamaDao().getPet(pet.id)!!.money
            assertTrue(engine.buyItem(potion.toInventoryItem(context), 1, potion.price, vendor).success)
            val actor = engine.world.state.value!!.actor
            assertEquals(vendor, actor.pendingActivity!!.destinationId)
            assertEquals(vendor, actor.pendingActivity!!.arguments["vendorId"])
            assertEquals(before, database.tamaDao().getPet(pet.id)!!.money)
            repeat(16) { if (engine.pet.value!!.inventory.none { it.id == itemId }) advance() }
            assertNull(engine.world.error.value)
            assertEquals(before - potion.price, database.tamaDao().getPet(pet.id)!!.money)
            assertEquals(1, engine.pet.value!!.inventory.single { it.id == itemId }.quantity)
            advance()
            assertEquals(before - potion.price, database.tamaDao().getPet(pet.id)!!.money)
        }
        val item = InventoryItem("water", "Water", ItemType.MATERIAL)
        assertFalse(engine.buyItem(item, 1, 5, "unknown_vendor").success)
    }

    @Test fun legacyAndGenericCatalogPurchasesCommitRealFoodExactlyOnce() = runBlocking {
        val pet = TamaPet(id = "legacy-buy", name = "Pixel", stage = GrowthStage.BABY,
            currentLocationId = LegacyLocationAliases.SHOP, money = 100L)
        start(pet)
        assertTrue(engine.buyItem("Apple", 10).success)
        advance()
        assertEquals(90L, engine.pet.value!!.money)
        assertEquals(1, engine.pet.value!!.inventory.single { it.id == "apple" }.quantity)
        advance()
        assertEquals(90L, engine.pet.value!!.money)
        assertTrue(engine.world.command(WorldCommand.PerformAction(ActionId.BUY, "bread",
            arguments = mapOf("itemId" to "bread", "quantity" to "1", "pricePerUnit" to "15"))).acceptedCommand)
        advance()
        assertNull(engine.world.error.value)
        assertEquals(75L, engine.pet.value!!.money)
        assertEquals(ItemType.FOOD, engine.pet.value!!.inventory.single { it.id == "bread" }.type)
    }

    @Test fun inventedOffersAndZeroPriceSalesAreRejectedWithoutPausingOrRemovingItems() = runBlocking {
        val crop = InventoryItem("crop_wheat", "Wheat", ItemType.CROP, quantity = 2)
        start(TamaPet(id = "invalid-trade", name = "Pixel", stage = GrowthStage.BABY,
            currentLocationId = LegacyLocationAliases.SHOP, money = 100L, inventory = listOf(crop)))
        assertFalse(engine.world.command(WorldCommand.PerformAction(ActionId.BUY, "imaginary_gem",
            arguments = mapOf("itemId" to "imaginary_gem", "quantity" to "1", "pricePerUnit" to "1"))).acceptedCommand)
        assertFalse(engine.sellItem(crop, 1, 0L).success)
        assertFalse(engine.sellItem(crop, 1, Long.MAX_VALUE).success)
        assertNull(engine.world.error.value)
        assertEquals(100L, engine.pet.value!!.money)
        assertEquals(listOf(crop), engine.pet.value!!.inventory)
    }

    @Test fun sharedInventoryUseRejectsToolsAndFrozenCareThenFeedsThroughSavedAction() = runBlocking {
        val tool = InventoryItem("axe", "Axe", ItemType.TOOL, durability = 100)
        val food = InventoryItem("apple", "Apple", ItemType.FOOD, quantity = 2)
        val pet = TamaPet(id = "inventory-use", name = "Pixel", stage = GrowthStage.BABY,
            stats = PetStats(hunger = 40f), inventory = listOf(tool, food))
        start(pet)
        assertFalse(engine.useInventoryItem("axe").success)
        assertFalse(engine.useInventoryItem("apple", 2).success)
        assertFalse(engine.wakeUp().success)
        assertEquals(pet.inventory, engine.pet.value!!.inventory)
        assertTrue(engine.freezeCycle().success)
        assertFalse(engine.useInventoryItem("apple").success)
        assertTrue(engine.unfreezeCycle().success)
        assertTrue(engine.useInventoryItem("apple").success)
        assertEquals(2, engine.pet.value!!.inventory.single { it.id == "apple" }.quantity)
        advance()
        assertEquals(1, engine.pet.value!!.inventory.single { it.id == "apple" }.quantity)
        assertEquals(55f, engine.pet.value!!.stats.hunger, 0.1f)
        assertEquals(tool, engine.pet.value!!.inventory.single { it.id == "axe" })
    }

    @Test fun genericSleepAtAlmostFullEnergyDoesNotApplyAnUpfrontEnergyGrant() = runBlocking {
        start(TamaPet(id = "almost-rested", name = "Pixel", stage = GrowthStage.BABY, stats = PetStats(energy = 98f)))
        assertTrue(engine.world.command(WorldCommand.PerformAction(ActionId.SLEEP)).acceptedCommand)
        advance()
        assertNull(engine.world.error.value)
        assertTrue(engine.pet.value!!.isSleeping)
        assertEquals(98f, engine.pet.value!!.stats.energy, 0.1f)
    }

    @Test fun adoptedPolicyHistoryRestoresWithoutReusingVersionsOrChangingPetProgress() = runBlocking {
        start(TamaPet(id = "policy-history", name = "Pixel", stage = GrowthStage.BABY, money = 123L))
        val policies = engine.world.policies
        val firstBytes = RecurrentPpoPolicy(seed = 101L).inferenceArtifact().toByteArray()
        val first = policies.adopt(firstBytes, "sourceCheckpoint=training-one")
        val second = policies.adopt(RecurrentPpoPolicy(seed = 202L).inferenceArtifact().toByteArray(), "sourceCheckpoint=training-two")
        assertEquals(2, policies.activeReference().version)
        assertEquals(second, policies.activeReference().id)
        policies.restore(first)
        assertEquals(first, policies.activeReference().id)
        assertArrayEquals(firstBytes, policies.activeReference().inferenceArtifact)
        assertEquals("training-one", policies.activeReference().sourceCheckpointId)
        val third = policies.adopt(RecurrentPpoPolicy(seed = 303L).inferenceArtifact().toByteArray(), "sourceCheckpoint=training-three")
        assertEquals(3, policies.activeReference().version)
        assertEquals(first, policies.history().single { it.id == third }.parentId)
        policies.restore(BASELINE_LIVING_POLICY_ID)
        assertNull(policies.activeArtifact())
        assertEquals(3, policies.history().count { !it.isBaseline })
        assertTrue(policies.history().single { it.isBaseline }.active)
        assertEquals(123L, engine.pet.value!!.money)
        assertTrue(engine.pet.value!!.inventory.isEmpty())
    }

    @Test fun sleepAndWakeUseCanonicalEnergyWithoutDuplicateWorldRewards() = runBlocking {
        val pet = TamaPet(id = "sleep-pet", name = "Pixel", stage = GrowthStage.BABY,
            stats = PetStats(energy = 40f))
        start(pet)
        assertTrue(engine.goToBed().success)
        assertFalse(database.tamaDao().getPet(pet.id)!!.isSleeping)
        advance()
        val sleeping = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertTrue(sleeping.isSleeping)
        assertEquals(40f, sleeping.stats.energy, 0f)
        engine.wakeUp()
        val awake = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertFalse(awake.isSleeping)
        assertEquals(50f, awake.stats.energy, 0f)
        engine.wakeUp()
        assertEquals(50f, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).stats.energy, 0f)
    }

    @Test fun selectedWorkJobStartsAfterActionAndFinishesWithOneCanonicalPayment() = runBlocking {
        val job = TamaWorkCatalog.jobs.first()
        val pet = TamaPet(id = "working-pet", name = "Pixel", stage = GrowthStage.TEEN,
            currentLocationId = LegacyLocationAliases.WORKPLACE, money = 0L,
            educationLevel = job.requiredEducation.toFloat())
        start(pet)
        assertTrue(engine.startWork(job.id).success)
        assertEquals(ActivityType.NONE, engine.pet.value!!.currentActivity)
        advance()
        val working = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertEquals(ActivityType.WORKING, working.currentActivity)
        assertEquals(job.id, working.currentWorkJobId)
        database.tamaDao().savePet(PetMapper.toEntity(working.copy(activityStartTime = System.currentTimeMillis() - 3_600_000L)))
        engine.reloadPersistedPet()
        assertEquals(job.hourlyPay.toLong(), engine.finishWork())
        assertEquals(job.hourlyPay.toLong(), engine.pet.value!!.money)
        assertEquals(ActivityType.NONE, engine.pet.value!!.currentActivity)
        assertEquals(0L, engine.finishWork())
        assertEquals(job.hourlyPay.toLong(), engine.pet.value!!.money)
    }

    @Test fun failedFarmCommitRestoresToolAndReloadedActionCanRetry() = runBlocking {
        val pet = TamaPet(id = "farm-pet", name = "Pixel", stage = GrowthStage.BABY,
            currentLocationId = LegacyLocationAliases.FARM,
            inventory = listOf(InventoryItem("hoe", "Hoe", ItemType.TOOL, durability = 100, maxDurability = 100)))
        start(pet)
        coEvery { farm.saveTile(any(), any(), any(), any()) } throws IllegalStateException("test_write_failure")
        val plot = engine.world.state.value!!.farmPlots.first()
        assertTrue(engine.world.command(WorldCommand.PerformAction(ActionId.TILL_SOIL, plot.id, plot.x, plot.y)).acceptedCommand)
        repeat(8) { if (engine.world.error.value == null) advance() }
        assertNotNull(engine.world.error.value)
        assertEquals(100, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).inventory.single().durability)
        assertEquals(TileStatus.SOIL, farm.getTiles(pet.id).first().status)
        assertEquals(ActionState.BLOCKED, WorldStateStore(database).load(pet.id)!!.actor.actionState)
        val failure = engine.world.error.value
        assertFalse(engine.world.command(WorldCommand.GoToStructure("unknown_facility")).acceptedCommand)
        assertEquals(failure, engine.world.error.value)
        assertEquals(ActionState.BLOCKED, engine.world.state.value!!.actor.actionState)

        coEvery { farm.saveTile(any(), any(), any(), any()) } coAnswers { callOriginal() }
        engine.world.invalidate()
        engine.world.advance(PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!), System.currentTimeMillis())
        assertNotNull(engine.world.error.value)
        assertTrue(engine.world.retry().acceptedCommand)
        repeat(8) { if (farm.getTiles(pet.id).first().status == TileStatus.SOIL) advance() }
        assertEquals(TileStatus.FARMLAND, farm.getTiles(pet.id).first().status)
        assertEquals(99, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).inventory.single().durability)
    }

    @Test fun failedDroneInstallationRollsBackPurchaseAndRetainsCanonicalRetryArguments() = runBlocking {
        val pet = TamaPet(id = "drone-pet", name = "Pixel", stage = GrowthStage.BABY,
            currentLocationId = LegacyLocationAliases.FARM, money = 10_000L)
        start(pet)
        coEvery { farm.buyUpgrade(any(), FARM_PLANTING_DRONE_ID, any(), any()) } throws IllegalStateException("test_install_failure")
        assertTrue(WorldFarmMaintenance.request(RuntimeEnvironment.getApplication(), engine, "buy_drone",
            mapOf("type" to FARM_PLANTING_DRONE_ID)).success)
        repeat(8) { if (engine.world.error.value == null) advance() }
        assertNotNull(engine.world.error.value)
        val rolledBack = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertEquals(pet.money, rolledBack.money)
        assertTrue(rolledBack.inventory.none { it.id == FARM_PLANTING_DRONE_ID })
        assertEquals(pet.money, engine.pet.value!!.money)
        val blocked = WorldStateStore(database).load(pet.id)!!
        assertEquals("farmMaintenance", blocked.actor.pendingCommand!!.arguments["canonicalAction"])

        coEvery { farm.buyUpgrade(any(), FARM_PLANTING_DRONE_ID, any(), any()) } coAnswers { callOriginal() }
        engine.world.invalidate()
        engine.world.advance(rolledBack, System.currentTimeMillis())
        assertTrue(engine.world.retry().acceptedCommand)
        repeat(8) { if (farm.getUpgrade(pet.id, FARM_PLANTING_DRONE_ID)?.isPurchased != true) advance() }
        assertNull(engine.world.error.value)
        assertNull(engine.world.state.value!!.actor.pendingCommand)
        val completed = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertEquals(pet.money - FARM_DRONE_BUY_PRICE, completed.money)
        assertEquals(1, completed.inventory.single { it.id == FARM_PLANTING_DRONE_ID }.quantity)
        assertTrue(farm.getUpgrade(pet.id, FARM_PLANTING_DRONE_ID)!!.isPurchased)
    }
}
