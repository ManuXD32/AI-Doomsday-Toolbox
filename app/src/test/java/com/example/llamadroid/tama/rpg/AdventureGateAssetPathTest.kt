package com.example.llamadroid.tama.rpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdventureGateAssetPathTest {

    @Test
    fun catalogAssetPathsExist() {
        AdventureGateCatalog.worlds.forEach { world ->
            assertAssetExists(world.mapIconAssetPath)
            assertAssetExists(world.worldMapAssetPath)
            world.phases.forEach { phase ->
                assertAssetExists(phase.backgroundAssetPath)
            }
        }
        val backgroundPaths = AdventureGateCatalog.worlds.flatMap { world ->
            world.phases.map { it.backgroundAssetPath }
        }.toSet()
        assertEquals(42, backgroundPaths.size)
        assertTrue(backgroundPaths.none { "/phase_" in it })
        AdventureGateCatalog.monsters.forEach { monster ->
            assertAssetExists("${monster.assetBasePath}/idle_0.png")
            listOf("idle_1.png", "attack_0.png", "hit_0.png", "rage_0.png").forEach { frame ->
                assertAssetMissing("${monster.assetBasePath}/$frame")
            }
        }
        AdventureGateCatalog.skills.forEach { skill ->
            assertAssetExists(AdventureGateCatalog.elementIconAssetPath(skill.element))
            repeat(3) { frame ->
                assertAssetExists("tama/adventure_gate/effects/${skill.id}/frame_$frame.png")
            }
        }
        AdventureGateElement.entries.forEach { element ->
            assertAssetExists(AdventureGateCatalog.elementIconAssetPath(element))
        }
        AdventureGateCatalog.statuses.forEach { status ->
            assertAssetExists(status.iconAssetPath)
        }
        AdventureGateCatalog.supplies.forEach { supply ->
            assertAssetExists(supply.assetPath)
        }
        AdventureGateCatalog.equipment.forEach { equipment ->
            assertAssetExists(equipment.assetPath)
        }
        assertAssetExists("tama/backgrounds/adventure_gate.png")
        assertAssetExists("tama/map/adventure_gate.png")
        assertAssetExists("tama/icons/ui/adventure_gate_portal.png")
    }

    private fun assertAssetExists(relativePath: String) {
        val candidates = listOf(
            File("app/src/main/assets/$relativePath"),
            File("src/main/assets/$relativePath")
        )
        assertTrue(
            "Missing Adventure Gate asset: $relativePath",
            candidates.any { it.isFile }
        )
    }

    private fun assertAssetMissing(relativePath: String) {
        val candidates = listOf(
            File("app/src/main/assets/$relativePath"),
            File("src/main/assets/$relativePath")
        )
        assertTrue(
            "Adventure Gate monster action frame should not be packaged: $relativePath",
            candidates.none { it.isFile }
        )
    }
}
