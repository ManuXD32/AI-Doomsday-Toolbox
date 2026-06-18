package com.example.llamadroid.tama.rpg

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.AdventureGateBattleStateEntity
import com.example.llamadroid.tama.db.AdventureGateNightArenaRunEntity
import com.example.llamadroid.tama.db.AdventureGateProfileEntity
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.PetMapper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class AdventureGateRepositoryRewardTest {
    @Test
    fun victoryRewards_areAddedToPersistedPetMoney() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val dao = database.tamaDao()
            val petId = "reward_pet"
            val startingMoney = 1_000L
            dao.savePet(
                PetMapper.toEntity(
                    TamaPet(
                        id = petId,
                        name = "Mochi",
                        stage = GrowthStage.ADULT,
                        money = startingMoney
                    )
                )
            )

            val stats = AdventureGateCombatEngine.baseStatsForLevel(50)
            dao.saveAdventureGateProfile(
                AdventureGateProfileEntity(
                    petId = petId,
                    level = 50,
                    maxHp = stats.maxHp,
                    maxMana = stats.maxMana,
                    attack = stats.attack,
                    magic = stats.magic,
                    defense = stats.defense,
                    speed = stats.speed,
                    accuracy = stats.accuracy,
                    evasion = stats.evasion,
                    currentHp = stats.maxHp,
                    currentMana = stats.maxMana
                )
            )

            val repository = AdventureGateRepository(database)
            val battle = requireNotNull(repository.startBattle(petId, "sproutvale_gate", 1))
            val result = requireNotNull(
                repository.performSkill(
                    petId = petId,
                    skillId = "paw_strike",
                    targetInstanceId = battle.enemies.first().instanceId
                )
            )

            assertTrue(result.snapshot.isVictory)
            val persistedPet = dao.getPet(petId)
            assertNotNull(persistedPet)
            assertEquals(
                startingMoney + AdventureGateCatalog.phaseCoinReward(
                    AdventureGateCatalog.world("sproutvale_gate").phases.first()
                ),
                persistedPet!!.money
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun nightArenaVictory_clearsNightLevelWithoutWorldProgressOrWakingPet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val dao = database.tamaDao()
            val petId = "night_reward_pet"
            val now = LocalDateTime.parse("2026-05-14T22:00:00")
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            dao.savePet(
                PetMapper.toEntity(
                    TamaPet(
                        id = petId,
                        name = "Luna",
                        stage = GrowthStage.ADULT,
                        money = 1_000L,
                        isSleeping = true,
                        sleepStartTime = now - 60_000
                    )
                )
            )

            val stats = AdventureGateCombatEngine.baseStatsForLevel(50)
            dao.saveAdventureGateProfile(
                AdventureGateProfileEntity(
                    petId = petId,
                    level = 50,
                    maxHp = stats.maxHp,
                    maxMana = stats.maxMana,
                    attack = stats.attack,
                    magic = stats.magic,
                    defense = stats.defense,
                    speed = stats.speed,
                    accuracy = stats.accuracy,
                    evasion = stats.evasion,
                    currentHp = stats.maxHp,
                    currentMana = stats.maxMana
                )
            )
            val level = NightArenaLevel(
                levelIndex = 1,
                sourceAdventureDepth = 1,
                waveMonsterIds = listOf(listOf("dewcap_slime")),
                backgroundAssetPath = AdventureGateCatalog.worlds.first().phases.first().backgroundAssetPath,
                enemyLevelOverride = 1,
                xpReward = 10,
                coinReward = 25,
                potionRewardChancePercent = 0,
                seed = 42L,
                nodeX = 0.5f,
                nodeY = 0.5f
            )
            dao.saveAdventureGateNightArenaRun(
                AdventureGateNightArenaRunEntity(
                    petId = petId,
                    nightKey = NightArenaGenerator.nightKeyFor(now),
                    levelsJson = Json.encodeToString(listOf(level)),
                    clearedLevelIdsJson = Json.encodeToString(emptySet<String>()),
                    createdAt = now,
                    updatedAt = now
                )
            )

            val repository = AdventureGateRepository(database)
            val battle = requireNotNull(repository.startNightArenaBattle(petId, 1, now))
            val result = requireNotNull(
                repository.performSkill(
                    petId = petId,
                    skillId = "paw_strike",
                    targetInstanceId = battle.enemies.first().instanceId
                )
            )

            assertTrue(result.snapshot.isVictory)
            assertTrue(dao.getAdventureGateWorldProgress(petId).isEmpty())
            val updatedRun = requireNotNull(dao.getAdventureGateNightArenaRun(petId))
            assertTrue(Json.decodeFromString<Set<String>>(updatedRun.clearedLevelIdsJson).contains("1"))
            assertNull(repository.startNightArenaBattle(petId, 1, now))
            val persistedPet = PetMapper.toDomain(requireNotNull(dao.getPet(petId)))
            assertTrue(persistedPet.isSleeping)
        } finally {
            database.close()
        }
    }

    @Test
    fun nightArenaDefeat_clearsLevelWithoutCarePenaltyOrWakingPet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val dao = database.tamaDao()
            val petId = "night_loss_pet"
            val now = LocalDateTime.parse("2026-05-14T22:00:00")
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            val startingStats = PetStats(happiness = 82f, health = 84f)
            dao.savePet(
                PetMapper.toEntity(
                    TamaPet(
                        id = petId,
                        name = "Luna",
                        stage = GrowthStage.ADULT,
                        stats = startingStats,
                        isSleeping = true,
                        sleepStartTime = now - 60_000
                    )
                )
            )

            val stats = AdventureGateCombatEngine.baseStatsForLevel(1)
            dao.saveAdventureGateProfile(
                AdventureGateProfileEntity(
                    petId = petId,
                    level = 1,
                    maxHp = stats.maxHp,
                    maxMana = stats.maxMana,
                    attack = stats.attack,
                    magic = stats.magic,
                    defense = stats.defense,
                    speed = stats.speed,
                    accuracy = stats.accuracy,
                    evasion = stats.evasion,
                    currentHp = stats.maxHp,
                    currentMana = stats.maxMana
                )
            )
            val level = NightArenaLevel(
                levelIndex = 1,
                sourceAdventureDepth = 1,
                waveMonsterIds = listOf(listOf("dewcap_slime")),
                backgroundAssetPath = AdventureGateCatalog.worlds.first().phases.first().backgroundAssetPath,
                enemyLevelOverride = 1,
                xpReward = 10,
                coinReward = 25,
                potionRewardChancePercent = 0,
                seed = 42L,
                nodeX = 0.5f,
                nodeY = 0.5f
            )
            dao.saveAdventureGateNightArenaRun(
                AdventureGateNightArenaRunEntity(
                    petId = petId,
                    nightKey = NightArenaGenerator.nightKeyFor(now),
                    levelsJson = Json.encodeToString(listOf(level)),
                    clearedLevelIdsJson = Json.encodeToString(emptySet<String>()),
                    createdAt = now,
                    updatedAt = now
                )
            )

            val repository = AdventureGateRepository(database)
            val battle = requireNotNull(repository.startNightArenaBattle(petId, 1, now))
            val doomedBattle = battle.copy(
                pet = battle.pet.copy(
                    hp = 1,
                    statuses = listOf(
                        AdventureGateStatusEffect(
                            id = "night_arena_test_fade",
                            turnsRemaining = 1,
                            damagePerTurn = 2
                        )
                    )
                )
            )
            dao.saveAdventureGateBattleState(
                AdventureGateBattleStateEntity(
                    petId = petId,
                    worldId = doomedBattle.worldId,
                    phaseNumber = doomedBattle.phaseNumber,
                    stateJson = Json.encodeToString(doomedBattle),
                    updatedAt = now
                )
            )

            val result = requireNotNull(repository.performSkill(petId, "guard", null))

            assertTrue(result.snapshot.isCompleted)
            assertTrue(!result.snapshot.isVictory)
            val updatedRun = requireNotNull(dao.getAdventureGateNightArenaRun(petId))
            assertTrue(Json.decodeFromString<Set<String>>(updatedRun.clearedLevelIdsJson).contains("1"))
            assertNull(repository.startNightArenaBattle(petId, 1, now))
            val persistedPet = PetMapper.toDomain(requireNotNull(dao.getPet(petId)))
            assertEquals(startingStats.happiness, persistedPet.stats.happiness, 0.001f)
            assertEquals(startingStats.health, persistedPet.stats.health, 0.001f)
            assertTrue(persistedPet.isSleeping)
        } finally {
            database.close()
        }
    }

    @Test
    fun adventureGateDefeat_keepsExistingCarePenalty() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val dao = database.tamaDao()
            val petId = "gate_loss_pet"
            val startingStats = PetStats(happiness = 82f, health = 84f)
            dao.savePet(
                PetMapper.toEntity(
                    TamaPet(
                        id = petId,
                        name = "Mochi",
                        stage = GrowthStage.ADULT,
                        stats = startingStats
                    )
                )
            )

            val stats = AdventureGateCombatEngine.baseStatsForLevel(1)
            dao.saveAdventureGateProfile(
                AdventureGateProfileEntity(
                    petId = petId,
                    level = 1,
                    maxHp = stats.maxHp,
                    maxMana = stats.maxMana,
                    attack = stats.attack,
                    magic = stats.magic,
                    defense = stats.defense,
                    speed = stats.speed,
                    accuracy = stats.accuracy,
                    evasion = stats.evasion,
                    currentHp = stats.maxHp,
                    currentMana = stats.maxMana
                )
            )

            val repository = AdventureGateRepository(database)
            val battle = requireNotNull(repository.startBattle(petId, "sproutvale_gate", 1))
            val doomedBattle = battle.copy(
                pet = battle.pet.copy(
                    hp = 1,
                    statuses = listOf(
                        AdventureGateStatusEffect(
                            id = "gate_test_fade",
                            turnsRemaining = 1,
                            damagePerTurn = 2
                        )
                    )
                )
            )
            dao.saveAdventureGateBattleState(
                AdventureGateBattleStateEntity(
                    petId = petId,
                    worldId = doomedBattle.worldId,
                    phaseNumber = doomedBattle.phaseNumber,
                    stateJson = Json.encodeToString(doomedBattle),
                    updatedAt = System.currentTimeMillis()
                )
            )

            val result = requireNotNull(repository.performSkill(petId, "guard", null))

            assertTrue(result.snapshot.isCompleted)
            assertTrue(!result.snapshot.isVictory)
            val persistedPet = PetMapper.toDomain(requireNotNull(dao.getPet(petId)))
            assertEquals(25f, persistedPet.stats.happiness, 0.001f)
            assertEquals(25f, persistedPet.stats.health, 0.001f)
        } finally {
            database.close()
        }
    }
}
