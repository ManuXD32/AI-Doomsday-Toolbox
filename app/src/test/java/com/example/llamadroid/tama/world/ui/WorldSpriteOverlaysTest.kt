package com.example.llamadroid.tama.world.ui

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldSpriteOverlaysTest {
    @Test
    fun missingDirectionTransformsRemainBackwardCompatibleDuringManifestDecode() {
        val entry = Json { ignoreUnknownKeys = true }.decodeFromString<WorldAssetEntry>(
            """
            {
              "id": "tool_axe",
              "path": "tama/world/tools/tool_axe.png",
              "frameWidth": 64,
              "frameHeight": 64,
              "columns": 3,
              "clips": [{"action": "SWING", "frames": [0, 1, 2], "frameMs": 150}]
            }
            """.trimIndent()
        )

        assertTrue(entry.directionTransforms.isEmpty())
        assertEquals(WorldAssetDirectionTransform(), entry.directionTransform(WorldDirection.NORTH_WEST))
    }

    @Test
    fun omittedFacingMetadataPreservesExistingFrontFacingOverlay() {
        val entry = toolEntry()

        assertEquals(WorldAssetDirectionTransform(), entry.directionTransform(WorldDirection.WEST))
        assertEquals(WorldAssetDirectionTransform(), entry.directionTransform(WorldDirection.SOUTH))
        assertEquals(3, entry.clips.single().frames.size)
        assertEquals(150, entry.clips.single().frameMs)
    }

    @Test
    fun axeFacingContractResolvesDiagonalDirectionsToCardinalMetadata() {
        val entry = toolEntry(
            directionTransforms = mapOf(
                "W" to WorldAssetDirectionTransform(),
                "E" to WorldAssetDirectionTransform(flipX = true),
                "N" to WorldAssetDirectionTransform(quarterTurnsClockwise = 1, behindActor = true),
                "S" to WorldAssetDirectionTransform(quarterTurnsClockwise = 3)
            )
        )

        assertFalse(entry.directionTransform(WorldDirection.WEST).flipX)
        assertTrue(entry.directionTransform(WorldDirection.EAST).flipX)
        assertTrue(entry.directionTransform(WorldDirection.NORTH).behindActor)
        assertEquals(1, entry.directionTransform(WorldDirection.NORTH_EAST).quarterTurnsClockwise)
        assertEquals(3, entry.directionTransform(WorldDirection.SOUTH_WEST).quarterTurnsClockwise)
    }

    private fun toolEntry(
        directionTransforms: Map<String, WorldAssetDirectionTransform> = emptyMap()
    ) = WorldAssetEntry(
        id = "tool_axe",
        path = "tama/world/tools/tool_axe.png",
        frameWidth = 64,
        frameHeight = 64,
        columns = 3,
        clips = listOf(WorldAssetClip("SWING", frames = listOf(0, 1, 2), frameMs = 150)),
        toolAnchors = mapOf(
            "0" to WorldAssetPoint(42f, 40f),
            "1" to WorldAssetPoint(40f, 36f),
            "2" to WorldAssetPoint(38f, 42f)
        ),
        directionTransforms = directionTransforms,
        productionReady = true
    )
}
