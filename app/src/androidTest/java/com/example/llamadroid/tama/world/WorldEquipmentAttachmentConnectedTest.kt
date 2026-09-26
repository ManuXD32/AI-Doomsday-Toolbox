package com.example.llamadroid.tama.world

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.llamadroid.tama.world.ui.WorldAssetClip
import com.example.llamadroid.tama.world.ui.WorldAssetDirectionTransform
import com.example.llamadroid.tama.world.ui.WorldAssetEntry
import com.example.llamadroid.tama.world.ui.WorldAssetPoint
import com.example.llamadroid.tama.world.ui.WorldDirection
import com.example.llamadroid.tama.world.ui.WorldSpriteAtlasEntry
import com.example.llamadroid.tama.world.ui.drawWorldTool
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production drawing path on Android Canvas, without a living DAO or game state. */
@RunWith(AndroidJUnit4::class)
class WorldEquipmentAttachmentConnectedTest {
    @Test
    fun authoredPhasesKeepTheirMatchedGripAcrossAllFacings() {
        val sheet = Bitmap.createBitmap(192, 64, Bitmap.Config.ARGB_8888)
        val phaseColors = listOf(Color.RED, Color.BLUE, Color.YELLOW)
        phaseColors.forEachIndexed { phase, color ->
            sheet.setPixel(phase * 64 + 44, 34, Color.GREEN)
            sheet.setPixel(phase * 64 + 36, 18, color)
        }
        val entry = WorldSpriteAtlasEntry(
            WorldAssetEntry(
                id = "attachment_probe",
                path = "test-fixture",
                frameWidth = 64,
                frameHeight = 64,
                columns = 3,
                clips = listOf(WorldAssetClip("SWING", frames = listOf(0, 1, 2), frameMs = 150)),
                // Pixel-centre coordinates make any pivot drift observable without rounding ambiguity.
                toolAnchors = (0..2).associate { it.toString() to WorldAssetPoint(44.5f, 34.5f) },
                directionTransforms = mapOf(
                    "W" to WorldAssetDirectionTransform(),
                    "E" to WorldAssetDirectionTransform(flipX = true),
                    "N" to WorldAssetDirectionTransform(quarterTurnsClockwise = 1, behindActor = true),
                    "S" to WorldAssetDirectionTransform(quarterTurnsClockwise = 3)
                )
            ),
            sheet.asImageBitmap()
        )
        val expectedHeadPixels = mapOf(
            WorldDirection.WEST to (56 to 48),
            WorldDirection.EAST to (72 to 48),
            WorldDirection.NORTH to (80 to 56),
            WorldDirection.SOUTH to (48 to 72)
        )
        try {
            expectedHeadPixels.forEach { (direction, head) ->
                phaseColors.forEachIndexed { phase, color ->
                    val rendered = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                    try {
                        CanvasDrawScope().draw(
                            density = Density(1f),
                            layoutDirection = LayoutDirection.Ltr,
                            canvas = Canvas(rendered.asImageBitmap()),
                            size = Size(128f, 128f)
                        ) {
                            drawWorldTool(entry, Offset(64.5f, 64.5f), 32f, phase * 150L, direction)
                        }
                        val label = "$direction phase $phase"
                        assertEquals("Grip drifted: $label", Color.GREEN, rendered.getPixel(64, 64))
                        assertEquals("Wrong pose or facing: $label", color, rendered.getPixel(head.first, head.second))
                        val pixels = IntArray(128 * 128)
                        rendered.getPixels(pixels, 0, 128, 0, 0, 128, 128)
                        assertEquals("Frame bled or filtering changed: $label", 2, pixels.count { Color.alpha(it) > 0 })
                    } finally {
                        rendered.recycle()
                    }
                }
            }
        } finally {
            sheet.recycle()
        }
    }
}
