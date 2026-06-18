package com.example.llamadroid.tama.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.PetSpriteState
import com.example.llamadroid.tama.data.TamaRoomCatalog
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.mapPetActionToSpriteState
import com.example.llamadroid.tama.data.resolvePetSpriteAssetPath

private const val PET_FRAME_DURATION_MS = 560
private const val WALK_FRAME_DURATION_MS = 240
private const val EAT_FRAME_DURATION_MS = 90
private const val EGG_IDLE_CYCLE_MS = 2400
private const val EGG_BLINK_WINDOW_MS = 220
private const val TAMA_PET_VISUAL_SCALE = 0.8f

@Composable
fun TamaPetSprite(
    pet: TamaPet,
    action: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp
) {
    val speciesLine = remember(pet.species, pet.genetics.bodyStyle) {
        PetSpeciesLine.fromSpeciesId(pet.species, pet.genetics.bodyStyle)
    }
    val spriteState = remember(action, pet.isSleeping) {
        mapPetActionToSpriteState(action, pet.isSleeping)
    }
    val frameIndex = rememberAnimatedPetFrame(pet.stage, spriteState)
    val assetPath = remember(speciesLine, pet.stage, spriteState, frameIndex) {
        resolvePetSpriteAssetPath(
            speciesLine = speciesLine,
            stage = pet.stage,
            state = spriteState,
            frameIndex = frameIndex
        )
    }

    AsyncImage(
        model = "file:///android_asset/$assetPath",
        contentDescription = null,
        modifier = modifier.size(size * TAMA_PET_VISUAL_SCALE),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
    )
}

@Composable
private fun rememberAnimatedPetFrame(stage: GrowthStage, state: PetSpriteState): Int {
    if (state.frameCount <= 1) return 0
    val infiniteTransition = rememberInfiniteTransition(label = "tama_pet_frame")
    val frameDurationMs = when (state) {
        PetSpriteState.WALK -> WALK_FRAME_DURATION_MS
        PetSpriteState.EAT -> EAT_FRAME_DURATION_MS
        else -> PET_FRAME_DURATION_MS
    }
    val cycleDurationMs = if (stage == GrowthStage.EGG && state == PetSpriteState.IDLE) {
        EGG_IDLE_CYCLE_MS
    } else {
        state.frameCount * frameDurationMs
    }
    val frameProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = cycleDurationMs.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(cycleDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tama_pet_frame_value"
    )
    if (stage == GrowthStage.EGG && state == PetSpriteState.IDLE) {
        return if (frameProgress < (EGG_IDLE_CYCLE_MS - EGG_BLINK_WINDOW_MS).toFloat()) 0 else 1
    }
    val frameIndex = (frameProgress / frameDurationMs.toFloat()).toInt() % state.frameCount
    return frameIndex.coerceIn(0, state.frameCount - 1)
}

@Composable
fun PixelScene(
    locationId: String,
    locationType: String? = null,
    pixelSize: Dp = 6.dp,
    modifier: Modifier = Modifier
) {
    val sceneKey = locationType?.lowercase() ?: when {
        locationId.contains("home", ignoreCase = true) -> "home"
        locationId.contains("shop", ignoreCase = true) -> "shop"
        locationId.contains("park", ignoreCase = true) -> "park"
        locationId.contains("school", ignoreCase = true) -> "school"
        locationId.contains("office", ignoreCase = true) || locationId.contains("work", ignoreCase = true) -> "office"
        locationId.contains("hospital", ignoreCase = true) -> "hospital"
        else -> "home"
    }

    val scene = TamaScenePatterns.LOCATION_SCENES[sceneKey] ?: TamaScenePatterns.LOCATION_SCENES.getValue("home")
    val patternWidth = scene.maxOfOrNull { it.length } ?: 16
    val patternHeight = scene.size

    Canvas(modifier = modifier.fillMaxSize()) {
        val availableWidth = size.width
        val availableHeight = size.height
        val targetPixelSize = maxOf(availableWidth / patternWidth, availableHeight / patternHeight, pixelSize.toPx() * 6f)
        val totalPatternWidth = patternWidth * targetPixelSize
        val totalPatternHeight = patternHeight * targetPixelSize
        val offsetX = (availableWidth - totalPatternWidth) / 2
        val offsetY = (availableHeight - totalPatternHeight) / 2

        scene.forEachIndexed { y, line ->
            line.forEachIndexed { x, char ->
                val color = when (char) {
                    '#' -> PixelBlack.copy(alpha = 0.5f)
                    '|', '=', '-', '_', '[', ']' -> PixelBlack.copy(alpha = 0.35f)
                    '/', '\\' -> PixelBlack.copy(alpha = 0.35f)
                    '+' -> Color.Red.copy(alpha = 0.25f)
                    'O' -> Color.Green.copy(alpha = 0.25f)
                    ' ' -> Color.White.copy(alpha = 0.05f)
                    else -> PixelTransparent
                }

                if (color != PixelTransparent) {
                    drawRect(
                        color = color,
                        topLeft = Offset(offsetX + x * targetPixelSize, offsetY + y * targetPixelSize),
                        size = Size(targetPixelSize, targetPixelSize)
                    )
                }
            }
        }
    }
}

@Composable
fun TamaLocationBackdrop(
    locationType: String?,
    homeRoomId: String? = null,
    assetPathOverride: String? = null,
    modifier: Modifier = Modifier
) {
    val sceneKey = locationType?.lowercase() ?: "home"
    val assetPath = remember(sceneKey, homeRoomId, assetPathOverride) {
        assetPathOverride ?: locationSceneAssetPath(sceneKey, homeRoomId)
    }
    if (assetPath != null) {
        AsyncImage(
            model = "file:///android_asset/$assetPath",
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None
        )
    } else {
        PixelScene(locationId = sceneKey, locationType = sceneKey, modifier = modifier)
    }
}

@Composable
fun PixelRoom(
    type: String = "bedroom",
    pixelSize: Dp = 6.dp,
    modifier: Modifier = Modifier
) {
    val pattern = TamaScenePatterns.ROOM_BED
    val lines = pattern.lines()
    val width = lines.maxOfOrNull { it.length } ?: 16
    val height = lines.size

    Canvas(
        modifier = modifier.size(
            width = pixelSize * width,
            height = pixelSize * height
        )
    ) {
        val pxSize = pixelSize.toPx()

        lines.forEachIndexed { y, line ->
            line.forEachIndexed { x, char ->
                val color = when (char) {
                    '#', 'Z' -> PixelBlack
                    else -> PixelTransparent
                }

                if (color != PixelTransparent) {
                    drawRect(
                        color = color,
                        topLeft = Offset(x * pxSize, y * pxSize),
                        size = Size(pxSize, pxSize)
                    )
                }
            }
        }
    }
}

// Pixel colors used by the scene renderer.
val PixelBlack = Color(0xFF1A1A1A)
val PixelTransparent = Color.Transparent

private fun locationSceneAssetPath(sceneKey: String, homeRoomId: String?): String? = when (sceneKey) {
    "home" -> TamaRoomCatalog.homeRoomAssetPath(homeRoomId)
    "bedroom" -> "tama/backgrounds/bedroom.png"
    "bathroom" -> "tama/backgrounds/bathroom.png"
    "school" -> "tama/backgrounds/classroom.png"
    "office", "workplace" -> "tama/backgrounds/workplace.png"
    "boxing_ring" -> "tama/backgrounds/boxing_ring.png"
    "park" -> "tama/backgrounds/park.png"
    "hospital" -> "tama/backgrounds/hospital.png"
    "farm" -> "tama/backgrounds/farm.png"
    "dungeon" -> "tama/backgrounds/dungeon.png"
    "adventure_gate" -> "tama/backgrounds/adventure_gate.png"
    "arcade" -> "tama/backgrounds/arcade_location.png"
    "shop" -> "tama/backgrounds/shop.png"
    "alchemist" -> "tama/backgrounds/alchemist.png"
    else -> null
}

object TamaScenePatterns {
    val ROOM_BED = """
        ................
        ................
        ....########....
        ...#........#...
        ...#..ZZZ...#...
        ...##########...
        ..#..........#..
        ..############..
        ................
    """.trimIndent()

    val LOCATION_SCENES = mapOf(
        "home" to listOf(
            "................",
            "................",
            "....|------|....",
            "....|      |....",
            "....|__  __|....",
            ".../        \\...",
            "..|          |..",
            "..|__________|.."
        ),
        "shop" to listOf(
            "################",
            "# [][][]  [][] #",
            "# [][][]  [][] #",
            "#              #",
            "#    ________  #",
            "#   |        | #",
            "#   |  SHOP  | #",
            "################"
        ),
        "park" to listOf(
            "..../\\..../\\....",
            ".../  \\../  \\...",
            "../    \\/    \\..",
            "................",
            "...___...___....",
            "../   \\./   \\...",
            ".|  O  |  O  |..",
            "..\\___/ \\___/..."
        ),
        "school" to listOf(
            "################",
            "#  __________  #",
            "# | A B C D  | #",
            "# | 1 2 3 4  | #",
            "# |__________| #",
            "#              #",
            "#    [====]    #",
            "################"
        ),
        "office" to listOf(
            "|==============|",
            "| [][][] [][][]|",
            "| [][][] [][][]|",
            "|              |",
            "|  _  _  _  _  |",
            "| | || || || | |",
            "|_|_||_||_||_|_|",
            "|==============|"
        ),
        "hospital" to listOf(
            "################",
            "#  +       +   #",
            "#      +       #",
            "#   ________   #",
            "#  |  ____  |  #",
            "#  | |____| |  #",
            "#  |________|  #",
            "################"
        )
    )
}
