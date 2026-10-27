package com.example.llamadroid.tama.rpg

import com.example.llamadroid.R
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureGateSnapshotPersistenceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun freshBattle(): AdventureGateBattleSnapshot {
        val phase = AdventureGateCatalog.world("sproutvale_gate").phases.first()
        return AdventureGateCombatEngine.startBattle(AdventureGateProfile(petId = "pet"), phase, seed = 5L)
    }

    @Test
    fun `every catalog name resource has a stable key that resolves back`() {
        val resources = AdventureGateCatalog.monsters.map { it.nameRes } +
            AdventureGateCatalog.skills.map { it.nameRes } +
            AdventureGateCatalog.enemyActions.map { it.nameRes } +
            R.string.adventure_gate_skill_skeleton_helper_attack
        resources.forEach { res ->
            val key = AdventureGateNameKeys.keyFor(res)
            assertNotNull("No stable key for $res", key)
            assertEquals(res, AdventureGateNameKeys.resFor(key!!))
        }
    }

    @Test
    fun `log entries persist stable keys instead of resource ids`() {
        val monster = AdventureGateCatalog.monsters.first()
        val skill = AdventureGateCatalog.skills.first()
        val entry = AdventureGateBattleLogEntry(
            messageKey = AdventureGateLogMessage.PET_USED_SKILL,
            skillId = skill.id,
            actorNameRes = monster.nameRes,
            targetNameRes = monster.nameRes,
            skillNameRes = skill.nameRes
        )
        val encoded = json.encodeToString(entry)
        assertFalse(encoded.contains("\"skillNameRes\":${skill.nameRes}"))
        assertTrue(encoded.contains("\"skillNameRes\":\"s:${skill.id}\""))
        val decoded = json.decodeFromString<AdventureGateBattleLogEntry>(encoded)
        assertEquals(monster.nameRes, decoded.actorNameRes)
        assertEquals(monster.nameRes, decoded.targetNameRes)
        assertEquals(skill.nameRes, decoded.skillNameRes)
    }

    @Test
    fun `legacy integer resource ids and unknown keys decode to no resource`() {
        val legacy = json.decodeFromString<AdventureGateBattleLogEntry>(
            """{"messageKey":"PET_USED_SKILL","actorNameRes":2131820001,"targetNameRes":7,"skillNameRes":-5}"""
        )
        assertNull(legacy.actorNameRes)
        assertNull(legacy.targetNameRes)
        assertNull(legacy.skillNameRes)
        val unknown = json.decodeFromString<AdventureGateBattleLogEntry>(
            """{"messageKey":"ENEMY_DEFEATED","targetNameRes":"m:not_a_monster","skillNameRes":null}"""
        )
        assertNull(unknown.targetNameRes)
        assertNull(unknown.skillNameRes)
    }

    @Test
    fun `night arena phase override round trips its story key`() {
        val level = NightArenaGenerator.generateRun("pet", "2026-05-14", sourceDepth = 3, nowMillis = 1L).levels.first()
        val phase = NightArenaGenerator.phaseForLevel(level)
        val decoded = json.decodeFromString<AdventureGatePhaseDefinition>(json.encodeToString(phase))
        assertEquals(R.string.night_arena_level_intro, decoded.storyRes)
        assertNull(decoded.bossRevealRes)
    }

    @Test
    fun `legacy phase override with integer story still decodes to the night arena intro`() {
        val decoded = json.decodeFromString<AdventureGatePhaseDefinition>(
            """{"worldId":"night_arena","phaseNumber":1,"waveMonsterIds":[],"isBoss":false,"backgroundAssetPath":"x","storyRes":99999,"bossRevealRes":88888}"""
        )
        assertEquals(R.string.night_arena_level_intro, decoded.storyRes)
        assertNull(decoded.bossRevealRes)
    }

    @Test
    fun `rewards granted flag defaults to false for old saves and survives a round trip`() {
        val battle = freshBattle()
        val old = json.decodeFromString<AdventureGateBattleSnapshot>(json.encodeToString(battle).replace(",\"rewardsGranted\":true", ""))
        assertFalse(old.rewardsGranted)
        val granted = json.decodeFromString<AdventureGateBattleSnapshot>(json.encodeToString(battle.copy(rewardsGranted = true)))
        assertTrue(granted.rewardsGranted)
    }

    @Test
    fun `a battle saved mid round on the enemy turn recovers to the pet turn`() {
        val stuck = freshBattle().copy(turn = AdventureGateTurn.ENEMY)
        val recovered = AdventureGateCombatEngine.recoverStuckTurn(stuck)
        assertEquals(AdventureGateTurn.PET, recovered.turn)
        val finished = stuck.copy(turn = AdventureGateTurn.COMPLETE, isCompleted = true, isVictory = true)
        assertEquals(finished, AdventureGateCombatEngine.recoverStuckTurn(finished))
        val result = AdventureGateCombatEngine.performSkill(
            AdventureGateProfile(petId = "pet"),
            recovered,
            "paw_strike",
            recovered.enemies.first().instanceId
        )
        assertTrue(result.snapshot.actionSequence > recovered.actionSequence)
    }

    @Test
    fun `finished battle is returned unchanged so repository can skip a second payout`() {
        val won = freshBattle().copy(
            turn = AdventureGateTurn.COMPLETE,
            isCompleted = true,
            isVictory = true,
            rewardsGranted = true
        )
        val result = AdventureGateCombatEngine.performSkill(
            AdventureGateProfile(petId = "pet"), won, "paw_strike", null
        )
        assertEquals(won, result.snapshot)
    }
}
