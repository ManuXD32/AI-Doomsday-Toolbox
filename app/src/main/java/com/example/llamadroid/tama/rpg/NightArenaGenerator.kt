package com.example.llamadroid.tama.rpg

import com.example.llamadroid.R
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.absoluteValue
import kotlin.random.Random

object NightArenaGenerator {
    private val ResetTime: LocalTime = LocalTime.of(21, 0)
    private val OpensAt: LocalTime = LocalTime.of(20, 0)
    private val ClosesAt: LocalTime = LocalTime.of(9, 0)
    private val NodePositions = listOf(
        0.18f to 0.70f,
        0.34f to 0.42f,
        0.52f to 0.62f,
        0.68f to 0.34f,
        0.82f to 0.56f
    )

    fun nightKeyFor(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val local = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDateTime()
        val date = if (local.toLocalTime().isBefore(ResetTime)) {
            local.toLocalDate().minusDays(1)
        } else {
            local.toLocalDate()
        }
        return date.toString()
    }

    fun nextResetAtMillis(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        val local = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDateTime()
        val resetToday = local.toLocalDate().atTime(ResetTime)
        val nextReset = if (local.isBefore(resetToday)) resetToday else resetToday.plusDays(1)
        return nextReset.atZone(zoneId).toInstant().toEpochMilli()
    }

    fun isActiveWindow(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        val time = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalTime()
        return !time.isBefore(OpensAt) || time.isBefore(ClosesAt)
    }

    fun sourceDepthForProgress(progressRows: List<AdventureGateWorldProgress>): Int {
        val progressByWorld = progressRows.associateBy { it.worldId }
        var nextDepth = 1
        AdventureGateCatalog.worlds.forEachIndexed { index, world ->
            if (index > 0) {
                val previousWorld = AdventureGateCatalog.worlds[index - 1]
                val previousCleared = progressByWorld[previousWorld.id]?.finalBossCleared == true
                if (!previousCleared) return nextDepth
            }
            val cleared = (progressByWorld[world.id]?.highestClearedPhase ?: 0)
                .coerceIn(0, AdventureGateCatalog.PHASES_PER_WORLD)
            if (cleared < AdventureGateCatalog.PHASES_PER_WORLD) {
                return index * AdventureGateCatalog.PHASES_PER_WORLD + cleared + 1
            }
            nextDepth = ((index + 1) * AdventureGateCatalog.PHASES_PER_WORLD + 1)
                .coerceAtMost(AdventureGateCatalog.WORLD_COUNT * AdventureGateCatalog.PHASES_PER_WORLD)
        }
        return AdventureGateCatalog.WORLD_COUNT * AdventureGateCatalog.PHASES_PER_WORLD
    }

    fun generateRun(
        petId: String,
        nightKey: String,
        sourceDepth: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): AdventureGateNightArenaRun {
        val seed = stableSeed(petId, nightKey)
        val rng = Random(seed)
        val levelCount = rng.nextInt(3, 6)
        val safeDepth = sourceDepth.coerceIn(1, AdventureGateCatalog.WORLD_COUNT * AdventureGateCatalog.PHASES_PER_WORLD)
        val levels = (1..levelCount).map { levelIndex ->
            generateLevel(
                index = levelIndex,
                sourceDepth = (safeDepth + levelIndex - 1)
                    .coerceAtMost(AdventureGateCatalog.WORLD_COUNT * AdventureGateCatalog.PHASES_PER_WORLD),
                rng = rng,
                seed = seed + levelIndex * 1_003L,
                node = NodePositions[levelIndex - 1]
            )
        }
        return AdventureGateNightArenaRun(
            petId = petId,
            nightKey = nightKey,
            levels = levels,
            createdAt = nowMillis,
            updatedAt = nowMillis
        )
    }

    fun phaseForLevel(level: NightArenaLevel): AdventureGatePhaseDefinition {
        return AdventureGatePhaseDefinition(
            worldId = AdventureGateCatalog.NIGHT_ARENA_WORLD_ID,
            phaseNumber = level.levelIndex,
            waveMonsterIds = level.waveMonsterIds,
            isBoss = false,
            backgroundAssetPath = level.backgroundAssetPath,
            storyRes = R.string.night_arena_level_intro,
            enemyLevelOverride = level.enemyLevelOverride,
            xpRewardOverride = level.xpReward,
            coinRewardOverride = level.coinReward,
            potionRewardChanceOverride = level.potionRewardChancePercent,
            sourceDepth = level.sourceAdventureDepth,
            nightArenaLevelId = level.id
        )
    }

    private fun generateLevel(
        index: Int,
        sourceDepth: Int,
        rng: Random,
        seed: Long,
        node: Pair<Float, Float>
    ): NightArenaLevel {
        val worldIndex = ((sourceDepth - 1) / AdventureGateCatalog.PHASES_PER_WORLD)
            .coerceIn(0, AdventureGateCatalog.WORLD_COUNT - 1)
        val phaseNumber = ((sourceDepth - 1) % AdventureGateCatalog.PHASES_PER_WORLD) + 1
        val world = AdventureGateCatalog.worlds[worldIndex]
        val monsterPool = reachedMonsterPool(worldIndex, phaseNumber).ifEmpty {
            AdventureGateCatalog.worlds.first().phases.first().waveMonsterIds.flatten()
        }
        val waveCount = when {
            index >= 4 -> rng.nextInt(2, 4)
            sourceDepth >= 20 -> rng.nextInt(1, 3)
            else -> 1
        }
        val waves = List(waveCount) { waveIndex ->
            val enemyCount = when {
                sourceDepth >= 60 -> rng.nextInt(2, AdventureGateCatalog.MAX_ENEMIES_PER_WAVE + 1)
                sourceDepth >= 25 || waveIndex > 0 -> rng.nextInt(1, 4)
                else -> rng.nextInt(1, 3)
            }
            List(enemyCount.coerceAtMost(AdventureGateCatalog.MAX_ENEMIES_PER_WAVE)) {
                monsterPool.random(rng)
            }
        }
        val enemyLevel = AdventureGateCombatEngine.enemyLevelFor(world.id, phaseNumber, boss = false) + (index - 1)
        val xpReward = waves.flatten()
            .sumOf { AdventureGateCatalog.monster(it).xpReward }
            .plus(sourceDepth * 2)
            .coerceAtLeast(10)
        val extraEnemies = waves.sumOf { (it.size - 1).coerceAtLeast(0) }
        val coinReward = (45 + sourceDepth * 9 + index * 18 + extraEnemies * 22).coerceAtLeast(50)
        return NightArenaLevel(
            levelIndex = index,
            sourceAdventureDepth = sourceDepth,
            waveMonsterIds = waves,
            backgroundAssetPath = randomBackground(rng),
            enemyLevelOverride = enemyLevel,
            xpReward = xpReward,
            coinReward = coinReward,
            potionRewardChancePercent = (18 + sourceDepth / 4 + index).coerceIn(18, 42),
            seed = seed,
            nodeX = node.first,
            nodeY = node.second
        )
    }

    private fun reachedMonsterPool(worldIndex: Int, phaseNumber: Int): List<String> =
        AdventureGateCatalog.worlds
            .take(worldIndex + 1)
            .flatMapIndexed { index, world ->
                val lastPhase = if (index == worldIndex) phaseNumber else AdventureGateCatalog.PHASES_PER_WORLD
                world.phases.take(lastPhase).flatMap { phase -> phase.waveMonsterIds.flatten() }
            }
            .distinct()
            .filterNot { monsterId -> AdventureGateCatalog.monster(monsterId).isBoss }

    private fun randomBackground(rng: Random): String {
        val backgrounds = AdventureGateCatalog.worlds.flatMap { world ->
            world.phases.map { it.backgroundAssetPath }
        }.distinct()
        return backgrounds.random(rng)
    }

    private fun stableSeed(petId: String, nightKey: String): Long {
        var hash = 1125899906842597L
        "$petId:$nightKey".forEach { char ->
            hash = 31L * hash + char.code
        }
        return hash.takeUnless { it == Long.MIN_VALUE }?.absoluteValue ?: 1L
    }
}
