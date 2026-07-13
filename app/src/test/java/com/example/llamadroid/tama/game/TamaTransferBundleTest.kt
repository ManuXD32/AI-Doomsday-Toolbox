package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.adventure.StorySchematic
import com.example.llamadroid.tama.data.TamaPet
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaTransferBundleTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `bundle round trip keeps full transfer sections`() {
        val pet = TamaPet(id = "pet-1", name = "Peque", introspectionLevel = 18.5f)
        val bundle = TamaTransferBundle(
            version = 2,
            exportDate = 1234L,
            pet = pet,
            events = listOf(
                TamaTransferEvent(
                    id = "event-1",
                    timestamp = 11L,
                    petId = pet.id,
                    eventType = "PLANTED",
                    details = "Planted Wheat Seeds (6 total)",
                    statsChangeJson = "{\"energy\":-6.0}"
                )
            ),
            chatMessages = listOf(
                TamaTransferChatMessage(
                    id = "chat-1",
                    petId = pet.id,
                    role = "assistant",
                    content = "Hola",
                    timestamp = 22L,
                    thinking = "brief"
                )
            ),
            summaries = listOf(
                TamaTransferSummary(
                    id = "summary-1",
                    petId = pet.id,
                    date = "2026-04-03",
                    summary = "Busy farm day",
                    createdAt = 33L,
                    lastEventTimestamp = 11L,
                    lastChatMessageTimestamp = 22L
                )
            ),
            settings = TamaTransferSettings(
                tamaNormalDreamingEnabled = false,
                tamaDeepDreamDesiredLanguage = "Spanish",
                tamaPetModel = "qwen-test",
                adventureWorldImageEnabled = true,
                adventureOnnxResolution = 768
            ),
            farmTiles = listOf(
                TamaTransferFarmTile(
                    id = 0,
                    petId = pet.id,
                    status = "PLANTED",
                    cropJson = "{\"cropType\":\"wheat\"}",
                    lastWateredTime = 44L
                )
            ),
            farmUpgrades = listOf(
                TamaTransferFarmUpgrade(
                    type = "well",
                    petId = pet.id,
                    isPurchased = true,
                    level = 2,
                    lastProductionTime = 55L,
                    storedOutput = 3
                )
            ),
            deepDreamRuns = listOf(
                TamaTransferDeepDreamRun(
                    id = "run-1",
                    petId = pet.id,
                    signature = "sig-1",
                    dreamDate = "2026-04-03",
                    status = "COMPLETED",
                    stage = "DONE",
                    albumId = "album-1",
                    ownsLocalLlama = true,
                    startedAt = 60L,
                    updatedAt = 61L,
                    lastHeartbeatAt = 62L
                )
            ),
            marketQuotes = listOf(
                TamaTransferMarketQuote(
                    petId = pet.id,
                    itemId = "crop_turnip",
                    quoteWeekKey = "2026-W14",
                    currentPrice = 42,
                    unitsSoldSinceRefresh = 7,
                    updatedAt = 63L
                )
            ),
            locations = listOf(
                TamaTransferLocation(
                    id = "park_center",
                    name = "Park Center",
                    type = "PARK",
                    description = "A cheerful plaza",
                    cityId = "pet_town",
                    x = 12,
                    y = 34,
                    isDiscovered = true,
                    npcIdsJson = """["npc-1"]""",
                    shopInventoryJson = """["seed_turnip"]""",
                    jobsJson = """["park_ranger"]"""
                )
            ),
            npcs = listOf(
                TamaTransferNpc(
                    id = "npc-1",
                    name = "Luna",
                    species = "cat",
                    personality = "kind",
                    geneticsJson = """{"eyeStyle":1}""",
                    homeLocationId = "park_center",
                    currentLocationId = "park_center",
                    job = "park_ranger",
                    age = 9,
                    marriedToPetId = null,
                    childrenIdsJson = "[]",
                    likesJson = """["flowers"]""",
                    dislikesJson = """["mud"]"""
                )
            ),
            adventureSessions = listOf(
                TamaTransferAdventureSession(
                    id = "session-1",
                    petId = pet.id,
                    dungeonType = "forest",
                    schematicJson = json.encodeToString(
                        StorySchematic(
                            totalStages = 5,
                            storyThread = "Forest mystery",
                            keyEvents = listOf("Trail", "Shrine"),
                            possibleEndings = listOf("Peace"),
                            tone = "Cozy",
                            difficulty = "Low",
                            worldImagePath = "/tmp/world.png"
                        )
                    ),
                    relativeWorldImagePath = "adventure_worlds/session-1.png",
                    currentStage = 2,
                    isCompleted = false,
                    cumulativeSummary = "Met a slime",
                    createdAt = 66L,
                    lastPlayedAt = 77L
                )
            ),
            adventureStages = listOf(
                TamaTransferAdventureStage(
                    id = "stage-1",
                    sessionId = "session-1",
                    stageNumber = 1,
                    storyContent = "A path opens",
                    userResponse = "Go left",
                    stageSummary = "Entered the forest",
                    imagePath = "/tmp/stage.png",
                    relativeImagePath = "adventure_stages/session-1/stage_1.png",
                    timestamp = 88L
                )
            ),
            dungeonProgress = TamaTransferDungeonProgress(
                petId = pet.id,
                completedDungeonCount = 4,
                lastCompletedDungeonType = "cave"
            ),
            adventureGateProfile = TamaTransferAdventureGateProfile(
                petId = pet.id,
                level = 12,
                xp = 345,
                maxHp = 230,
                maxMana = 95,
                attack = 40,
                magic = 38,
                defense = 34,
                speed = 16,
                accuracy = 104,
                evasion = 9,
                currentHp = 177,
                currentMana = 61,
                skillPoints = 5,
                purchasedSkillIdsJson = """["paw_strike","spark","guard","heal_dew"]""",
                learnedAttackIdsJson = """["paw_strike","quick_claw"]""",
                equippedAttackIdsJson = """["paw_strike","quick_claw"]""",
                learnedMagicIdsJson = """["spark","guard","heal_dew"]""",
                equippedMagicIdsJson = """["spark","heal_dew"]""",
                equippedWeaponId = "ag_weapon_sprout_baton",
                equippedShieldId = "ag_shield_leaf_shell",
                equippedRingId = "ag_ring_dewdrop",
                equippedRelicId = "ag_relic_nexum_heart",
                lastRecoveryAt = 98L,
                updatedAt = 99L
            ),
            adventureGateWorldProgress = listOf(
                TamaTransferAdventureGateWorldProgress(
                    petId = pet.id,
                    worldId = "sproutvale_gate",
                    highestClearedPhase = 7,
                    midBossCleared = true,
                    finalBossCleared = false,
                    updatedAt = 100L
                )
            ),
            adventureGateBattleState = TamaTransferAdventureGateBattleState(
                petId = pet.id,
                worldId = "sproutvale_gate",
                phaseNumber = 8,
                stateJson = """{"petId":"pet-1","skillCooldowns":{"quick_claw":1},"guardUses":2}""",
                updatedAt = 101L
            ),
            adventureGateNightArenaRun = TamaTransferAdventureGateNightArenaRun(
                petId = pet.id,
                nightKey = "2026-04-03-night",
                levelsJson = """[{"levelIndex":0,"levelId":"night-0"}]""",
                clearedLevelIdsJson = """["night-0"]""",
                createdAt = 102L,
                updatedAt = 103L
            )
        )

        val parsed = parseTamaTransferBundle(json.encodeToString(bundle), json)

        assertEquals(pet.id, parsed.pet.id)
        assertEquals(18.5f, parsed.pet.introspectionLevel, 0.001f)
        assertEquals(1, parsed.events.size)
        assertEquals(1, parsed.chatMessages.size)
        assertEquals(1, parsed.summaries.size)
        assertEquals("qwen-test", parsed.settings?.tamaPetModel)
        assertEquals(1, parsed.farmTiles.size)
        assertEquals(1, parsed.farmUpgrades.size)
        assertEquals(1, parsed.deepDreamRuns.size)
        assertEquals(1, parsed.marketQuotes.size)
        assertEquals(42, parsed.marketQuotes.first().currentPrice)
        assertEquals(1, parsed.locations.size)
        assertEquals("park_center", parsed.locations.first().id)
        assertEquals(1, parsed.npcs.size)
        assertEquals("npc-1", parsed.npcs.first().id)
        assertEquals(1, parsed.adventureSessions.size)
        assertEquals(1, parsed.adventureStages.size)
        assertEquals("adventure_worlds/session-1.png", parsed.adventureSessions.first().relativeWorldImagePath)
        assertEquals("adventure_stages/session-1/stage_1.png", parsed.adventureStages.first().relativeImagePath)
        assertEquals("cave", parsed.dungeonProgress?.lastCompletedDungeonType)
        assertEquals(12, parsed.adventureGateProfile?.level)
        assertEquals(61, parsed.adventureGateProfile?.currentMana)
        assertEquals("ag_relic_nexum_heart", parsed.adventureGateProfile?.equippedRelicId)
        assertEquals(1, parsed.adventureGateWorldProgress.size)
        assertEquals(7, parsed.adventureGateWorldProgress.first().highestClearedPhase)
        assertEquals("sproutvale_gate", parsed.adventureGateBattleState?.worldId)
        assertTrue(parsed.adventureGateBattleState?.stateJson.orEmpty().contains("skillCooldowns"))
        assertEquals("2026-04-03-night", parsed.adventureGateNightArenaRun?.nightKey)
    }

    @Test
    fun `legacy export parses with empty defaults for new sections`() {
        val legacyJson = """
            {
              "version": 1,
              "exportDate": 1000,
              "pet": {
                "id": "pet-legacy",
                "name": "Legacy",
                "species": "dragon",
                "birthTimestamp": 1,
                "lastDecayTime": 2,
                "stage": "BABY",
                "stats": {
                  "hunger": 90.0,
                  "happiness": 80.0,
                  "health": 70.0,
                  "energy": 60.0,
                  "hygiene": 50.0
                },
                "mood": "HAPPY",
                "personality": "CHEERFUL",
                "genetics": {
                  "eyeStyle": 0,
                  "earStyle": 0,
                  "mouthStyle": 0,
                  "headShape": 0,
                  "bodyStyle": 0,
                  "armStyle": 0,
                  "legStyle": 0,
                  "colorTint": 0,
                  "accessories": []
                },
                "relationships": {},
                "ownerBondLevel": 50.0,
                "educationLevel": 0.0,
                "currentLocationId": "home",
                "money": 100,
                "inventory": [],
                "currentActivity": "NONE",
                "activityStartTime": null,
                "isSleeping": false,
                "sleepStartTime": null,
                "lastSleepWarningTime": null,
                "miscareCount": 0,
                "isMad": false,
                "discoveredLocationIds": ["home"]
              },
              "events": [
                {
                  "id": "event-legacy",
                  "petId": "pet-legacy",
                  "eventType": "PLANTED",
                  "details": "Planted Wheat Seeds",
                  "timestamp": 12
                }
              ],
              "summaries": [
                {
                  "id": "summary-legacy",
                  "petId": "pet-legacy",
                  "date": "2026-04-03",
                  "summary": "Legacy summary",
                  "createdAt": 13
                }
              ]
            }
        """.trimIndent()

        val parsed = parseTamaTransferBundle(legacyJson, json)

        assertEquals(1, parsed.version)
        assertEquals("pet-legacy", parsed.pet.id)
        assertEquals(0f, parsed.pet.introspectionLevel, 0.001f)
        assertEquals(1, parsed.events.size)
        assertEquals(1, parsed.summaries.size)
        assertTrue(parsed.chatMessages.isEmpty())
        assertTrue(parsed.farmTiles.isEmpty())
        assertTrue(parsed.farmUpgrades.isEmpty())
        assertNull(parsed.settings)
        assertTrue(parsed.deepDreamRuns.isEmpty())
        assertTrue(parsed.marketQuotes.isEmpty())
        assertTrue(parsed.locations.isEmpty())
        assertTrue(parsed.npcs.isEmpty())
        assertTrue(parsed.adventureSessions.isEmpty())
        assertTrue(parsed.adventureStages.isEmpty())
        assertEquals(null, parsed.dungeonProgress)
        assertEquals(null, parsed.adventureGateNightArenaRun)
    }

    @Test
    fun `replacement pet ids clears all existing pets plus imported pet`() {
        val ids = replacementPetIds(
            existingPetIds = listOf("pet-a", "pet-b", " ", "pet-a"),
            importedPetId = "pet-c"
        )

        assertEquals(setOf("pet-a", "pet-b", "pet-c"), ids)
    }

    @Test
    fun `adventure session restore patches schematic world image path`() {
        val transfer = TamaTransferAdventureSession(
            id = "session-1",
            petId = "pet-1",
            dungeonType = "forest",
            schematicJson = json.encodeToString(
                StorySchematic(
                    totalStages = 5,
                    storyThread = "Forest mystery",
                    keyEvents = listOf("Trail"),
                    possibleEndings = listOf("Peace"),
                    tone = "Cozy",
                    difficulty = "Low",
                    worldImagePath = "/old/path/world.png"
                )
            ),
            relativeWorldImagePath = "adventure_worlds/session-1.png",
            currentStage = 1,
            isCompleted = false,
            cumulativeSummary = "Started",
            createdAt = 1L,
            lastPlayedAt = 2L
        )

        val entity = transfer.toEntity("/restored/session-1.png")

        assertEquals("/restored/session-1.png", decodeSchematicWorldImagePath(entity.schematicJson))
    }
}
