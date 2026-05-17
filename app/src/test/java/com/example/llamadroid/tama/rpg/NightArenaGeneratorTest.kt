package com.example.llamadroid.tama.rpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NightArenaGeneratorTest {
    private val zone = ZoneId.of("Europe/Madrid")

    @Test
    fun `night key rolls over at 9 pm local time`() {
        assertEquals(
            "2026-05-13",
            NightArenaGenerator.nightKeyFor(millis("2026-05-14T20:59:00"), zone)
        )
        assertEquals(
            "2026-05-14",
            NightArenaGenerator.nightKeyFor(millis("2026-05-14T21:00:00"), zone)
        )
        assertEquals(
            "2026-05-14",
            NightArenaGenerator.nightKeyFor(millis("2026-05-15T00:30:00"), zone)
        )
    }

    @Test
    fun `next reset uses the next 9 pm boundary`() {
        assertEquals(
            millis("2026-05-14T21:00:00"),
            NightArenaGenerator.nextResetAtMillis(millis("2026-05-14T20:59:00"), zone)
        )
        assertEquals(
            millis("2026-05-15T21:00:00"),
            NightArenaGenerator.nextResetAtMillis(millis("2026-05-14T21:00:00"), zone)
        )
    }

    @Test
    fun `active window opens at 8 pm and closes at 9 am`() {
        assertEquals(false, NightArenaGenerator.isActiveWindow(millis("2026-05-14T19:59:00"), zone))
        assertEquals(true, NightArenaGenerator.isActiveWindow(millis("2026-05-14T20:00:00"), zone))
        assertEquals(true, NightArenaGenerator.isActiveWindow(millis("2026-05-15T08:59:00"), zone))
        assertEquals(false, NightArenaGenerator.isActiveWindow(millis("2026-05-15T09:00:00"), zone))
    }

    @Test
    fun `same pet and night generate the same levels while next night changes`() {
        val first = NightArenaGenerator.generateRun(
            petId = "pet-a",
            nightKey = "2026-05-14",
            sourceDepth = 12,
            nowMillis = 10L
        )
        val repeated = NightArenaGenerator.generateRun(
            petId = "pet-a",
            nightKey = "2026-05-14",
            sourceDepth = 12,
            nowMillis = 20L
        )
        val nextNight = NightArenaGenerator.generateRun(
            petId = "pet-a",
            nightKey = "2026-05-15",
            sourceDepth = 12,
            nowMillis = 30L
        )

        assertEquals(first.levels, repeated.levels)
        assertNotEquals(first.levels, nextNight.levels)
        assertTrue(first.levels.size in 3..5)
        first.levels.forEach { level ->
            assertTrue(level.waveMonsterIds.isNotEmpty())
            assertTrue(level.waveMonsterIds.all { wave -> wave.size <= AdventureGateCatalog.MAX_ENEMIES_PER_WAVE })
            assertTrue(level.backgroundAssetPath in AdventureGateCatalog.worlds.flatMap { world ->
                world.phases.map { phase -> phase.backgroundAssetPath }
            })
        }
    }

    @Test
    fun `generated night levels never include boss monsters`() {
        val runs = (1..20).map { index ->
            NightArenaGenerator.generateRun(
                petId = "pet-$index",
                nightKey = "2026-05-${10 + index}",
                sourceDepth = AdventureGateCatalog.WORLD_COUNT * AdventureGateCatalog.PHASES_PER_WORLD,
                nowMillis = index.toLong()
            )
        }

        val generatedMonsterIds = runs
            .flatMap { run -> run.levels }
            .flatMap { level -> level.waveMonsterIds.flatten() }

        assertTrue(generatedMonsterIds.isNotEmpty())
        assertTrue(generatedMonsterIds.none { monsterId ->
            AdventureGateCatalog.monster(monsterId).isBoss
        })
    }

    @Test
    fun `source depth defaults to one and follows adventure progress`() {
        val firstWorld = AdventureGateCatalog.worlds[0]
        val secondWorld = AdventureGateCatalog.worlds[1]

        assertEquals(1, NightArenaGenerator.sourceDepthForProgress(emptyList()))
        assertEquals(
            5,
            NightArenaGenerator.sourceDepthForProgress(
                listOf(
                    AdventureGateWorldProgress(
                        petId = "pet",
                        worldId = firstWorld.id,
                        highestClearedPhase = 4
                    )
                )
            )
        )
        assertEquals(
            18,
            NightArenaGenerator.sourceDepthForProgress(
                listOf(
                    AdventureGateWorldProgress(
                        petId = "pet",
                        worldId = firstWorld.id,
                        highestClearedPhase = AdventureGateCatalog.PHASES_PER_WORLD,
                        finalBossCleared = true
                    ),
                    AdventureGateWorldProgress(
                        petId = "pet",
                        worldId = secondWorld.id,
                        highestClearedPhase = 2
                    )
                )
            )
        )
    }

    private fun millis(localDateTime: String): Long =
        LocalDateTime.parse(localDateTime)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
