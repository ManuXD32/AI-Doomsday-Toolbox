package com.example.llamadroid.tama.adventure

import com.example.llamadroid.tama.rpg.AdventureGateCatalog
import com.example.llamadroid.tama.rpg.AdventureGateWorldProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureChronicleLinkTest {
    private val hero = AdventureHero(
        name = "Mochi", species = "fox", stage = "child", personality = "shy", personalityNote = "Quiet",
        mood = "happy", condition = emptyList(), gateLevel = 3, gateGear = emptyList()
    )
    private val schematic = StorySchematic(
        totalStages = 10, storyThread = "x", keyEvents = emptyList(), possibleEndings = emptyList(),
        tone = "cozy", difficulty = "medium"
    )
    private val worldIds = AdventureGateCatalog.worlds.map { it.id }

    private fun cleared(count: Int) = worldIds.take(count).map {
        AdventureGateWorldProgress(petId = "p", worldId = it, highestClearedPhase = 15, midBossCleared = true, finalBossCleared = true)
    }

    @Test
    fun unlockedWorldsFollowFinalBossChain() {
        assertEquals(worldIds.take(1), AdventureChronicleLink.unlockedWorldIds(emptyList()))
        assertEquals(worldIds.take(3), AdventureChronicleLink.unlockedWorldIds(cleared(2)))
        assertEquals(worldIds, AdventureChronicleLink.unlockedWorldIds(cleared(worldIds.size)))
    }

    @Test
    fun chaosWorldPickIsDeterministicAndOnlyUnlocked() {
        val unlocked = worldIds.take(4)
        repeat(20) { n ->
            val id = "session-$n"
            val first = AdventureChronicleLink.pickChaosWorld(id, unlocked)
            assertEquals(first, AdventureChronicleLink.pickChaosWorld(id, unlocked))
            assertTrue(first in unlocked)
            assertEquals(
                AdventureChronicleLink.cameoMonsterIds(first!!, id),
                AdventureChronicleLink.cameoMonsterIds(first, id)
            )
        }
        assertNull(AdventureChronicleLink.pickChaosWorld("x", emptyList()))
        // The pick actually varies with the session id.
        assertTrue((0 until 40).map { AdventureChronicleLink.pickChaosWorld("s$it", unlocked) }.toSet().size > 1)
    }

    @Test
    fun cameosAreOrdinaryMonstersOfThatWorld() {
        worldIds.forEach { worldId ->
            val ids = AdventureChronicleLink.cameoMonsterIds(worldId, "abc")
            assertEquals(AdventureChronicleLink.CAMEO_COUNT, ids.size)
            assertEquals(ids.size, ids.toSet().size)
            val inWorld = AdventureGateCatalog.world(worldId).phases.flatMap { it.waveMonsterIds.flatten() }.toSet()
            ids.forEach { id ->
                assertTrue(id in inWorld)
                val monster = AdventureGateCatalog.monster(id)
                assertFalse(monster.isBoss)
                assertNull(monster.eliteOfId)
            }
        }
    }

    @Test
    fun promptsContainWorldLoreAndCameoNames() {
        val world = AdventureLore.loreFor("en").world(worldIds.first())!!
        val names = listOf("Bramble Guard", "Moss Sleeper", "Sprout Imp")
        val block = AdventureChronicleLink.chaosBlock(world, names)
        names.forEach { assertTrue(it, block.contains(it)) }
        assertTrue(block.contains(world.mood))
        assertTrue(block.contains(world.landmarks.first()))
        assertTrue(block.contains(world.inhabitants))

        val planner = AdventurePrompts.plannerPrompt("English", DungeonType.CHAOS_REALM, hero, gateVisit = block)
        val header = AdventurePrompts.sceneHeader("English", DungeonType.CHAOS_REALM, schematic, hero, AdventureRunState(), "Chaos", block)
        names.forEach {
            assertTrue(planner.contains(it))
            assertTrue(header.contains(it))
        }
        assertFalse(AdventurePrompts.plannerPrompt("English", DungeonType.CHAOS_REALM, hero).contains("GATE WORLD VISIT"))
    }

    @Test
    fun grantResolutionIsDeterministicAndIdempotent() {
        val grants = listOf(ChronicleGrant("gate_item"), ChronicleGrant("memory_fragment"), ChronicleGrant("lantern_spark"))
        val rows = cleared(1)
        val first = AdventureChronicleLink.resolveGrants(grants, "s1", rows, emptySet())
        assertEquals(first, AdventureChronicleLink.resolveGrants(grants, "s1", rows, emptySet()))
        // Resolving an already resolved list changes nothing, so a repeated grant cannot pick something new.
        assertEquals(first, AdventureChronicleLink.resolveGrants(first, "other", cleared(3), setOf("x#0")))

        val supplyId = AdventureChronicleLink.supplyIdOf(first[0])
        assertNotNull(AdventureGateCatalog.supply(supplyId!!))
        val fragmentId = AdventureChronicleLink.fragmentIdOf(first[1])!!
        assertTrue(AdventureChronicleLink.fragmentText("en", fragmentId) != null)
        assertTrue(AdventureChronicleLink.fragmentText("es", fragmentId) != null)
        assertTrue(AdventureChronicleLink.isSpark(first[2]))
    }

    @Test
    fun fragmentsSkipUnlockedOnesAndFallBackToCoins() {
        val rows = cleared(2)
        val gateUnlocked = AdventureChronicleLink.gateUnlockedFragmentIds(rows)
        assertEquals(4, gateUnlocked.size)
        repeat(30) { n ->
            val id = AdventureChronicleLink.pickFragment("s$n", gateUnlocked)!!
            assertFalse(id in gateUnlocked)
        }
        val all = AdventureLore.loreFor("en").worlds.flatMap { w -> w.journalFragments.indices.map { "${w.worldId}#$it" } }.toSet()
        assertNull(AdventureChronicleLink.pickFragment("s", all))
        val fallback = AdventureChronicleLink.resolveGrants(listOf(ChronicleGrant("memory_fragment")), "s", emptyList(), all)
        assertEquals(AdventureChronicleLink.FALLBACK_COINS, AdventureChronicleLink.fallbackCoinsOf(fallback.single()))
        val unknown = AdventureChronicleLink.resolveGrants(listOf(ChronicleGrant("mystery")), "s", emptyList(), emptySet())
        assertEquals(AdventureChronicleLink.FALLBACK_COINS, AdventureChronicleLink.fallbackCoinsOf(unknown.single()))
    }

    @Test
    fun logUpsertNeverDoubleCountsAndSurvivesJson() {
        val grants = AdventureChronicleLink.resolveGrants(
            listOf(ChronicleGrant("lantern_spark"), ChronicleGrant("memory_fragment")), "s1", emptyList(), emptySet()
        )
        val entry = ChronicleLogEntry("s1", DungeonType.CHAOS_REALM.name, "T", 100L, AdventureOutcome.VICTORY, 10, 2, 1, grants)
        val once = ChronicleLog().upsert(entry)
        val twice = once.upsert(entry)
        assertEquals(1, twice.entries.size)
        assertEquals(1, twice.sparks)
        assertEquals(1, twice.fragmentIds.size)
        val second = twice.upsert(entry.copy(sessionId = "s2", finishedAt = 200L))
        assertEquals(2, second.sparks)
        assertEquals(second, ChronicleLog.decode(ChronicleLog.encode(second)))
        assertEquals(ChronicleLog(), ChronicleLog.decode("not json"))
    }

    @Test
    fun relitCornersGrowWithSparksAndAreBilingual() {
        assertEquals(0, AdventureChronicleLink.relitCornerLines("en", 0).size)
        assertEquals(3, AdventureChronicleLink.relitCornerLines("es", 3).size)
        assertEquals(AdventureChronicleLink.relitCorners.size, AdventureChronicleLink.relitCornerLines("en", 99).size)
    }

    @Test
    fun spanishLoreUsesShippedNames() {
        val es = AdventureLore.loreFor("es")
        val text = buildString {
            append(es.premise).append(es.toneGuide).append(es.regent.backstory).append(es.regent.epilogue)
            es.worlds.forEach { w -> append(w.inhabitants).append(w.landmarks.joinToString()).append(w.introBeat).append(w.outroBeat) }
            es.dungeons.forEach { append(it.stylePrompt).append(it.chronicleReward) }
        }
        assertFalse(text.contains("Nexum"))
        assertFalse(text.contains("Guardián de Zarzas"))
        assertFalse(text.contains("Portal"))
        assertTrue(text.contains("Nexo"))
    }
}
