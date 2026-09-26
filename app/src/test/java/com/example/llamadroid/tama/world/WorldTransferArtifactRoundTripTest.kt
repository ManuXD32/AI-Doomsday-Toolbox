package com.example.llamadroid.tama.world

import android.app.Application
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.FARMLAND_UPGRADE_ID
import com.example.llamadroid.tama.data.TamaArtworkKind
import com.example.llamadroid.tama.data.TamaArtworkStatus
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.PlantedCrop
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TileStatus
import com.example.llamadroid.tama.data.WellSlot
import com.example.llamadroid.tama.data.WellUpgradeState
import com.example.llamadroid.tama.db.FarmTileEntity
import com.example.llamadroid.tama.db.FarmUpgradeEntity
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaArtworkManager
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.TamaTransferBundle
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.tama.world.core.AutonomyLevel
import com.example.llamadroid.tama.world.core.AutonomyPolicy
import com.example.llamadroid.tama.world.core.EventImportance
import com.example.llamadroid.tama.world.core.GoalId
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import com.example.llamadroid.tama.world.core.WorldDelta
import com.example.llamadroid.tama.world.core.WorldDeltaKind
import com.example.llamadroid.tama.world.core.WorldCommand
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptRequest
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptResult
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import com.example.llamadroid.tama.world.persistence.TamaWorldEpisodeEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldEventEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldRelationshipEntity
import com.example.llamadroid.tama.world.persistence.WorldStateStore
import com.example.llamadroid.tama.world.persistence.WorldTransfers
import com.example.llamadroid.tama.world.policy.RecurrentPpoPolicy
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WorldTransferArtifactRoundTripTest {
    private lateinit var database: TamaDatabase
    private lateinit var farm: FarmRepository
    private lateinit var settings: SettingsRepository
    private lateinit var engine: TamaGameEngine
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    private data class Fixture(
        val pet: TamaPet,
        val policyBytes: ByteArray,
        val farmTiles: List<FarmTileEntity>,
        val farmUpgrades: List<FarmUpgradeEntity>,
        val relationship: TamaWorldRelationshipEntity,
        val event: TamaWorldEventEntity,
        val episode: TamaWorldEpisodeEntity,
        val receipt: TamaWorldActionReceiptEntity,
        val artwork: com.example.llamadroid.tama.db.TamaArtworkEntity,
        val artworkBytes: ByteArray
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        settings = SettingsRepository(context)
        settings.setTamaNormalDreamingEnabled(false)
        settings.setTamaDeepDreamingEnabled(false)
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
            settings,
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
    fun v21JsonRoundTripCarriesLivingWorldPolicyRelationshipsReceiptPetAndFarm() = runBlocking {
        val fixture = seedRichTransfer()

        val payload = engine.exportToJson()
        val root = json.parseToJsonElement(payload).jsonObject
        assertEquals(21, root.getValue("version").jsonPrimitive.int)
        assertTrue(root.containsKey("livingWorld"))
        val exported = json.decodeFromString<TamaTransferBundle>(payload)
        val exportedWorld = requireNotNull(exported.livingWorld)
        val exportedSettings = requireNotNull(exported.settings)
        assertFalse(exportedSettings.tamaNormalDreamingEnabled)
        assertFalse(exportedSettings.tamaDeepDreamingEnabled)
        assertNotNull(exportedWorld.adoptedPolicies.singleOrNull { it.active })
        assertEquals(fixture.relationship, exportedWorld.relationships.single())
        assertEquals(fixture.event, exportedWorld.events.single())
        assertEquals(fixture.episode, exportedWorld.episodes.single())
        assertEquals(fixture.receipt, exportedWorld.actionReceipts.single())
        assertEquals(fixture.farmTiles, exported.farmTiles.map { it.toEntity() })
        assertEquals(fixture.farmUpgrades, exported.farmUpgrades.map { it.toEntity() })

        assertTrue(engine.importFromJson(payload))

        val restoredWorld = requireNotNull(WorldTransfers.export(database, fixture.pet.id))
        assertEquals(exportedWorld, restoredWorld)
        val restoredPolicy = restoredWorld.adoptedPolicies.single { it.active }
        assertArrayEquals(fixture.policyBytes, Base64.getDecoder().decode(restoredPolicy.inferenceArtifact))
        assertEquals(fixture.receipt, database.worldActionReceiptDao().byId(fixture.pet.id, fixture.receipt.id))
        assertEquals(fixture.farmTiles, database.farmDao().getTiles(fixture.pet.id))
        assertEquals(fixture.farmUpgrades, database.farmDao().getUpgrades(fixture.pet.id))
        assertEquals(fixture.artwork, database.tamaDao().getArtwork(fixture.artwork.id))
        assertArrayEquals(fixture.artworkBytes, File(fixture.artwork.filePath!!).readBytes())
        assertFalse(settings.tamaNormalDreamingEnabled.value)
        assertFalse(settings.tamaDeepDreamingEnabled.value)

        val restoredPet = PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(fixture.pet.id)))
        assertEquals(fixture.pet.name, restoredPet.name)
        assertEquals(fixture.pet.stage, restoredPet.stage)
        assertEquals(fixture.pet.stats, restoredPet.stats)
        assertEquals(fixture.pet.money, restoredPet.money)
        assertEquals(fixture.pet.inventory, restoredPet.inventory)
        assertEquals(fixture.pet.currentLocationId, restoredPet.currentLocationId)
    }

    @Test
    fun v21ZipRoundTripRestoresMediaWorldAndAdoptedPolicy() = runBlocking {
        val fixture = seedRichTransfer()
        val expectedWorld = requireNotNull(WorldTransfers.export(database, fixture.pet.id))
        val backup = ByteArrayOutputStream()
        assertTrue(engine.exportToBackupZip(backup))
        File(fixture.artwork.filePath!!).writeText("changed after backup")

        assertTrue(engine.importFromBackup(ByteArrayInputStream(backup.toByteArray())))

        assertEquals(listOf(fixture.pet.id), database.tamaDao().getAllPetIds())
        assertEquals(expectedWorld, WorldTransfers.export(database, fixture.pet.id))
        assertEquals(fixture.farmTiles, database.farmDao().getTiles(fixture.pet.id))
        assertEquals(fixture.farmUpgrades, database.farmDao().getUpgrades(fixture.pet.id))
        val artwork = requireNotNull(database.tamaDao().getArtwork(fixture.artwork.id))
        assertArrayEquals(fixture.artworkBytes, File(requireNotNull(artwork.filePath)).readBytes())
        val restored = PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(fixture.pet.id)))
        assertEquals(fixture.pet.stats, restored.stats)
        assertEquals(fixture.pet.inventory, restored.inventory)
        assertEquals(fixture.pet.money, restored.money)
    }

    @Test
    fun v20JsonWithoutLivingWorldRemainsAccepted() = runBlocking {
        val fixture = seedRichTransfer()
        val encoded = json.encodeToJsonElement(
            TamaTransferBundle(version = 20, exportDate = 1234L, pet = fixture.pet)
        ).jsonObject
        val withoutWorld = JsonObject(encoded - "livingWorld")
        assertEquals(20, withoutWorld.getValue("version").jsonPrimitive.int)
        assertFalse(withoutWorld.containsKey("livingWorld"))

        assertTrue(engine.importFromJson(withoutWorld.toString()))
        assertNull(WorldTransfers.export(database, fixture.pet.id))
        val restoredPet = PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(fixture.pet.id)))
        assertEquals(fixture.pet.name, restoredPet.name)
        assertEquals(fixture.pet.money, restoredPet.money)
    }

    @Test
    fun failedV21ZipImportPreservesCurrentPetWorldDatabaseAndMedia() = runBlocking {
        val fixture = seedRichTransfer()
        val beforePet = requireNotNull(database.tamaDao().getPet(fixture.pet.id))
        val beforeWorld = requireNotNull(WorldTransfers.export(database, fixture.pet.id))
        val beforeFarmTiles = database.farmDao().getTiles(fixture.pet.id)
        val beforeFarmUpgrades = database.farmDao().getUpgrades(fixture.pet.id)
        val beforeArtwork = requireNotNull(database.tamaDao().getArtwork(fixture.artwork.id))
        val beforeMedia = File(fixture.artwork.filePath!!).readBytes()

        val validManifest = engine.exportToJson()
        val invalidManifest = invalidGeneratorManifest(validManifest)

        val validArchive = zipManifest(validManifest)
        val missingEndRecord = validArchive.copyOf(validArchive.size - 22)
        for (invalidArchive in listOf(zipManifest(invalidManifest), missingEndRecord)) {
            assertFalse(engine.importFromBackup(ByteArrayInputStream(invalidArchive)))
            assertEquals(beforePet, database.tamaDao().getPet(fixture.pet.id))
            assertEquals(beforeWorld, WorldTransfers.export(database, fixture.pet.id))
            assertEquals(beforeFarmTiles, database.farmDao().getTiles(fixture.pet.id))
            assertEquals(beforeFarmUpgrades, database.farmDao().getUpgrades(fixture.pet.id))
            assertEquals(beforeArtwork, database.tamaDao().getArtwork(fixture.artwork.id))
            assertArrayEquals(beforeMedia, File(fixture.artwork.filePath!!).readBytes())
        }
    }

    @Test
    fun v20ImportReplacesHigherStageActivePetWithImportedLowerStagePet() = runBlocking {
        assertLowerStageImportWins(version = 20)
    }

    @Test
    fun v21ImportReplacesHigherStageActivePetWithImportedLowerStagePet() = runBlocking {
        assertLowerStageImportWins(version = 21)
    }

    private suspend fun assertLowerStageImportWins(version: Int) {
        val now = System.currentTimeMillis()
        val existing = transferPet("existing-adult", "Existing", GrowthStage.ADULT, now)
        val imported = transferPet("imported-baby", "Imported", GrowthStage.BABY, now)
        database.tamaDao().savePet(PetMapper.toEntity(existing))
        engine.reloadPersistedPet()

        val bundle = TamaTransferBundle(
            version = version,
            exportDate = now,
            pet = imported
        )
        val encoded = JsonObject(json.encodeToJsonElement(bundle).jsonObject - "livingWorld")

        assertTrue(engine.importFromJson(encoded.toString()))
        assertEquals(listOf(imported.id), database.tamaDao().getAllPetIds())
        assertEquals(imported.id, database.tamaDao().getActivePet()?.id)
        assertEquals(imported.name, PetMapper.toDomain(requireNotNull(database.tamaDao().getActivePet())).name)
    }

    private fun transferPet(id: String, name: String, stage: GrowthStage, now: Long): TamaPet {
        return TamaPet(
            id = id,
            name = name,
            stage = stage,
            cycleFrozen = true,
            cycleFreezeStartedAt = now,
            birthTimestamp = now - 123_000L,
            stageProgressStartTime = now,
            lastDecayTime = now,
            poopCount = 1,
            poopCreatedAt = now,
            nextPoopAt = now + 86_400_000L
        )
    }

    private fun invalidGeneratorManifest(validManifest: String): String {
        val root = json.parseToJsonElement(validManifest).jsonObject
        val livingWorld = requireNotNull(root["livingWorld"]).jsonObject
        val state = requireNotNull(livingWorld["state"]).jsonObject
        val invalidState = JsonObject(state + ("generatorVersion" to JsonPrimitive(999)))
        val invalidWorld = JsonObject(livingWorld + ("state" to invalidState))
        return JsonObject(root + ("livingWorld" to invalidWorld)).toString()
    }

    private fun zipManifest(manifest: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private suspend fun seedRichTransfer(): Fixture {
        val now = System.currentTimeMillis()
        val pet = TamaPet(
            id = "transfer-artifact-pet",
            name = "Pixel",
            stage = GrowthStage.SENIOR,
            cycleFrozen = true,
            cycleFreezeStartedAt = now,
            birthTimestamp = now - 123_000L,
            stageProgressStartTime = now,
            lastDecayTime = now,
            stats = PetStats(hunger = 61f, happiness = 72f, health = 83f, energy = 54f, hydration = 67f),
            money = 777L,
            inventory = listOf(InventoryItem("apple", "Apple", ItemType.FOOD, quantity = 3)),
            currentLocationId = "home",
            poopCount = 1,
            poopCreatedAt = now,
            nextPoopAt = now + 86_400_000L
        )
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        engine.reloadPersistedPet()
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)

        val policyBytes = RecurrentPpoPolicy(seed = 6_161L).inferenceArtifact().toByteArray()
        val policyId = engine.world.policies.adopt(policyBytes, "sourceCheckpoint=transfer-artifact-test")
        val current = requireNotNull(engine.world.state.value)
        val npcId = current.npcs.firstOrNull()?.id ?: "npc-transfer"
        val enriched = current.copy(
            tick = 321L,
            autonomy = AutonomyPolicy(
                level = AutonomyLevel.NORMAL,
                allowPurchases = true,
                maximumAutonomousPurchase = 40,
                allowWork = true
            ),
            timezoneOffsetMinutes = 60,
            deltas = current.deltas + WorldDelta(
                x = 10,
                y = 10,
                kind = WorldDeltaKind.EXPLORATION,
                value = "observed",
                updatedAtTick = 321L
            ),
            actor = current.actor.copy(
                goal = GoalId.EXPLORE,
                navigationMemory = listOf(0.125f, -0.25f, 0.5f),
                policyVersion = policyId
            )
        )
        WorldStateStore(database).save(enriched, current)
        engine.world.invalidate()

        val plantedCrop = PlantedCrop(
            type = "wheat",
            stage = 1,
            plantedTime = now - 10_000L,
            lastStageUpdateTime = now - 5_000L,
            isFertilized = true
        )
        val farmUpgrades = listOf(
            FarmUpgradeEntity(
                type = FARMLAND_UPGRADE_ID,
                petId = pet.id,
                isPurchased = true,
                level = 1,
                lastProductionTime = now
            ),
            FarmUpgradeEntity(
                type = "well",
                petId = pet.id,
                isPurchased = true,
                level = 2,
                lastProductionTime = now - 2_000L,
                storedOutput = 1,
                extraDataJson = Json.encodeToString(
                    WellUpgradeState(
                        speedLevel = 1,
                        slots = listOf(WellSlot(hasWater = true, cycleStartedAt = now - 2_000L))
                    )
                )
            )
        )
        database.farmDao().saveUpgrades(farmUpgrades)
        // The level-one farmland upgrade unlocks the canonical second page.
        // Capture all rows after the repository applies that rule so the
        // transfer assertion exercises the real farm shape, including the
        // planted crop rather than a synthetic one-row fixture.
        val farmTiles = farm.ensureUnlockedFarmTiles(pet.id).map { tile ->
            if (tile.id == 2) {
                FarmTileEntity(
                    id = tile.id,
                    petId = pet.id,
                    status = TileStatus.WET_FARMLAND.name,
                    cropJson = Json.encodeToString(plantedCrop),
                    lastWateredTime = now - 1_000L
                )
            } else {
                FarmTileEntity(
                    id = tile.id,
                    petId = pet.id,
                    status = tile.status.name,
                    cropJson = tile.crop?.let { crop -> Json.encodeToString(crop) },
                    lastWateredTime = tile.lastWateredTime
                )
            }
        }
        database.farmDao().saveTiles(farmTiles)

        val artworkBytes = byteArrayOf(0x01, 0x23, 0x45, 0x67)
        val artworkFile = TamaArtworkManager.artworkFile(
            RuntimeEnvironment.getApplication(),
            pet.id,
            "transfer-artwork"
        )
        artworkFile.writeBytes(artworkBytes)
        val artwork = com.example.llamadroid.tama.db.TamaArtworkEntity(
            id = "transfer-artwork",
            petId = pet.id,
            kind = TamaArtworkKind.PAINTING.name,
            status = TamaArtworkStatus.COMPLETED.name,
            title = "Transfer art",
            prompt = "test",
            negativePrompt = "",
            modelFilename = "test-model",
            modelLabel = "Test model",
            width = 2,
            height = 2,
            steps = 1,
            cfgScale = 1f,
            seed = 7L,
            sourceActivity = "test",
            albumId = null,
            albumIndex = 0,
            albumDate = null,
            albumSummary = null,
            filePath = artworkFile.absolutePath,
            errorMessage = null,
            createdAt = now,
            startedAt = now,
            completedAt = now
        )
        database.tamaDao().saveArtwork(artwork)

        val worldId = enriched.worldId
        val relationship = TamaWorldRelationshipEntity(pet.id, npcId, 7f, 44f, 12f, now, 3)
        val event = TamaWorldEventEntity(
            id = "transfer-world-event",
            worldId = worldId,
            petId = pet.id,
            timestamp = now,
            importance = EventImportance.NOTABLE.name,
            actorId = pet.id,
            eventType = "transfer_checkpoint",
            payload = "{\"source\":\"test\"}",
            memoryEligible = true,
            episodeId = "transfer-world-episode"
        )
        val episode = TamaWorldEpisodeEntity(
            id = "transfer-world-episode",
            worldId = worldId,
            petId = pet.id,
            startTime = now - 1_000L,
            endTime = now,
            title = "Transfer checkpoint",
            summary = "Persistent living-world state",
            importance = EventImportance.NOTABLE.name,
            memoryStatus = "PENDING",
            evidenceJson = "[\"transfer-world-event\"]"
        )
        database.worldDao().saveRelationships(listOf(relationship))
        database.worldDao().saveEvents(listOf(event))
        database.worldDao().saveEpisodes(listOf(episode))

        val request = TamaWorldActionReceiptRequest(
            receiptId = "transfer-receipt",
            petId = pet.id,
            worldId = worldId,
            kind = TamaWorldActionReceiptKind.PARK_QUEST_FINISH,
            destinationId = LegacyLocationAliases.PARK,
            targetNpcId = npcId,
            requestedAt = now,
            questId = "transfer-quest"
        )
        val receipt = TamaWorldActionReceiptEntity(
            petId = pet.id,
            id = request.receiptId,
            worldId = worldId,
            kind = request.kind,
            requestJson = Json.encodeToString(request),
            resultJson = Json.encodeToString(TamaWorldActionReceiptResult(
                success = true,
                message = "Transferred",
                action = request.kind,
                completedAt = now
            )),
            status = TamaWorldActionReceiptStatus.SUCCEEDED,
            createdAt = now,
            updatedAt = now,
            completedAt = now
        )
        database.worldActionReceiptDao().save(receipt)
        return Fixture(
            PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(pet.id))),
            policyBytes,
            farmTiles,
            farmUpgrades,
            relationship,
            event,
            episode,
            receipt,
            artwork,
            artworkBytes
        )
    }
}
