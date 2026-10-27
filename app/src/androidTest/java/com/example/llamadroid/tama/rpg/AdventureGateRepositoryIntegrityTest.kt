package com.example.llamadroid.tama.rpg

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.AdventureGateProfileEntity
import com.example.llamadroid.tama.db.AdventureGateWorldProgressEntity
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.PetMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdventureGateRepositoryIntegrityTest {
    private suspend fun seed(database: TamaDatabase, petId: String, money: Long) {
        val dao = database.tamaDao()
        dao.savePet(PetMapper.toEntity(TamaPet(id = petId, name = "Mochi", stage = GrowthStage.ADULT, money = money)))
        val stats = AdventureGateCombatEngine.baseStatsForLevel(50)
        dao.saveAdventureGateProfile(
            AdventureGateProfileEntity(
                petId = petId, level = 50, maxHp = stats.maxHp, maxMana = stats.maxMana,
                attack = stats.attack, magic = stats.magic, defense = stats.defense, speed = stats.speed,
                accuracy = stats.accuracy, evasion = stats.evasion, currentHp = stats.maxHp, currentMana = stats.maxMana
            )
        )
    }

    private fun database(): TamaDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        TamaDatabase::class.java
    ).allowMainThreadQueries().build()

    @Test
    fun finishedVictory_isPaidOnlyOnceEvenWhenActionIsRepeated() = runBlocking {
        val database = database()
        try {
            val petId = "double_pay_pet"
            seed(database, petId, money = 100L)
            val repository = AdventureGateRepository(database)
            val battle = requireNotNull(repository.startBattle(petId, "sproutvale_gate", 1))
            val target = battle.enemies.first().instanceId
            val first = requireNotNull(repository.performSkill(petId, "paw_strike", target))
            assertTrue(first.snapshot.isVictory)
            assertTrue(first.snapshot.rewardsGranted)
            val moneyAfterFirst = database.tamaDao().getPet(petId)!!.money
            assertTrue(moneyAfterFirst > 100L)
            repeat(3) { repository.performSkill(petId, "paw_strike", target) }
            assertEquals(moneyAfterFirst, database.tamaDao().getPet(petId)!!.money)
        } finally {
            database.close()
        }
    }

    @Test
    fun startBattleResult_rejectsLockedWorldsLockedPhasesAndSecondBattles() = runBlocking {
        val database = database()
        try {
            val petId = "start_guard_pet"
            seed(database, petId, money = 0L)
            val repository = AdventureGateRepository(database)
            assertEquals(
                AdventureGateStartError.WORLD_LOCKED,
                repository.startBattleResult(petId, "ember_toyworks", 1).error
            )
            assertEquals(
                AdventureGateStartError.PHASE_LOCKED,
                repository.startBattleResult(petId, "sproutvale_gate", 2).error
            )
            assertEquals(
                AdventureGateStartError.UNKNOWN_WORLD,
                repository.startBattleResult(petId, "missing_world", 1).error
            )
            assertNotNull(repository.startBattleResult(petId, "sproutvale_gate", 1).snapshot)
            assertEquals(
                AdventureGateStartError.BATTLE_ACTIVE,
                repository.startBattleResult(petId, "sproutvale_gate", 1).error
            )
            database.tamaDao().saveAdventureGateWorldProgress(
                AdventureGateWorldProgressEntity(petId = petId, worldId = "sproutvale_gate", highestClearedPhase = 1)
            )
            repository.abandonBattle(petId)
            assertNull(repository.startBattleResult(petId, "sproutvale_gate", 2).error)
        } finally {
            database.close()
        }
    }

    @Test
    fun staleEnemyTurnSave_loadsAsPetTurn() = runBlocking {
        val database = database()
        try {
            val petId = "stuck_turn_pet"
            seed(database, petId, money = 0L)
            val repository = AdventureGateRepository(database)
            val battle = requireNotNull(repository.startBattle(petId, "sproutvale_gate", 1))
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val stuck = battle.copy(turn = AdventureGateTurn.ENEMY)
            database.tamaDao().saveAdventureGateBattleState(
                com.example.llamadroid.tama.db.AdventureGateBattleStateEntity(
                    petId = petId, worldId = stuck.worldId, phaseNumber = stuck.phaseNumber,
                    stateJson = json.encodeToString(AdventureGateBattleSnapshot.serializer(), stuck),
                    updatedAt = stuck.updatedAt
                )
            )
            val loaded = repository.observeBattle(petId).first()
            assertEquals(AdventureGateTurn.PET, loaded?.turn)
        } finally {
            database.close()
        }
    }
}
