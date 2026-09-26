package com.example.llamadroid.tama.world.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val WORLD_TILE_LOGICAL_SIZE = 32f
private const val WORLD_CANVAS_MARGIN_TILES = 2
private const val WORLD_CANVAS_HEIGHT_FRACTION = 0.40f
private const val WORLD_CANVAS_MIN_HEIGHT_DP = 260
private const val WORLD_CANVAS_MAX_HEIGHT_DP = 420
private const val WORLD_MAX_INSPECTOR_HEIGHT_DP = 264
private const val WORLD_MINIMAP_SIZE_DP = 116
private const val WORLD_DOUBLE_TAP_WINDOW_MS = 320L
private const val WORLD_GESTURE_ZOOM_SLOP = 0.01f

private val WorldCanvasGrass = Color(0xFF73A85E)
private val WorldCanvasForest = Color(0xFF4E855C)
private val WorldCanvasWater = Color(0xFF5E9CC2)
private val WorldCanvasSand = Color(0xFFD2B263)
private val WorldCanvasRock = Color(0xFF8E8C82)
private val WorldCanvasSnow = Color(0xFFD7E7EB)
private val WorldCanvasDirt = Color(0xFFAD825A)
private val WorldCanvasWetSoil = Color(0xFF806044)
private val WorldCanvasDrySoil = Color(0xFFAD825A)
private val WorldCanvasMystic = Color(0xFF6D629B)
private val WorldCanvasFog = Color(0xFF30343C)
private val WorldCanvasGrid = Color.White.copy(alpha = 0.045f)
private val WorldCanvasOutline = Color(0xFF1E2730)

private data class WorldSpriteDraw(
    val depth: Float,
    val assetId: String,
    val position: WorldPointUi,
    val widthTiles: Int,
    val heightTiles: Int,
    val action: String,
    val direction: WorldDirection,
    val frameIndex: Int,
    val selected: Boolean,
    val actorId: String? = null,
    val toolAsset: String? = null,
    val effectAsset: String? = null
)

/**
 * World route entry point. The controller owns [WorldUiState]; every visible
 * action exits through [WorldUiCallbacks.onCommand].
 */
@Composable
fun WorldScreen(
    state: WorldUiState,
    callbacks: WorldUiCallbacks,
    modifier: Modifier = Modifier,
    spriteAtlas: WorldSpriteAtlas? = null,
    loadAssets: Boolean = true,
    exitShortcutLabelRes: Int? = R.string.tama_world_shortcut_room,
    isSimulationActive: Boolean = false,
    homeActionLabelRes: Int = R.string.tama_world_open_home,
    onSystemBack: (() -> Unit)? = null
) {
    BackHandler(enabled = isSimulationActive && onSystemBack != null) {
        onSystemBack?.invoke()
    }
    val visibleAssetIds = remember(state.tiles, state.actors, state.structures, state.resources, state.farmTiles, state.minimap) {
        buildSet {
            addAll(state.tiles.asSequence().filter { it.known }.map(WorldTileUi::terrainId))
            addAll(state.actors.map(WorldActorUi::assetId))
            addAll(state.structures.filter(WorldStructureUi::discovered).map(WorldStructureUi::assetId))
            addAll(state.resources.map(WorldResourceUi::assetId))
            addAll(state.minimap?.markers.orEmpty().mapNotNull(WorldMiniMapMarkerUi::assetId))
            state.farmTiles.asSequence()
                .filter { it.unlocked }
                .mapNotNull(WorldFarmTileUi::renderCropAssetId)
                .forEach(::add)
            add("actor_shadow")
            addAll(listOf("edge_grass_dirt", "edge_grass_sand", "edge_grass_rock", "edge_snow_grass", "edge_water_shore", "edge_mud_water"))
            state.actors.forEach { actor ->
                worldToolAsset(actor.action)?.let(::add)
                worldActionEffect(actor.action)?.let(::add)
                if (worldActionEffect(actor.action, water = true) == "fx_water_step") add("fx_water_step")
            }
        }
    }
    val loadedAssets = if (loadAssets) {
        rememberWorldSpriteAtlas(visibleAssetIds = visibleAssetIds).value
    } else {
        null
    }
    val atlas = spriteAtlas ?: loadedAssets?.atlas ?: WorldSpriteAtlas.Empty
    val readiness = if (spriteAtlas != null) {
        state.assetReadiness
    } else {
        loadedAssets?.readiness ?: state.assetReadiness
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val canvasHeight = (maxHeight * WORLD_CANVAS_HEIGHT_FRACTION).coerceIn(
            WORLD_CANVAS_MIN_HEIGHT_DP.dp,
            WORLD_CANVAS_MAX_HEIGHT_DP.dp
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            WorldHud(state.hud, callbacks, homeActionLabelRes)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeight)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = WorldCanvasOutline),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    WorldCanvas(
                        state = state,
                        callbacks = callbacks,
                        atlas = atlas,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                state.minimap?.let { minimap ->
                    WorldMinimap(
                        minimap = minimap,
                        atlas = atlas,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                    )
                }

                state.inspector?.let { inspector ->
                    WorldInspectorCard(
                        inspector = inspector,
                        callbacks = callbacks,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(10.dp)
                    )
                }
            }

            if (state.activeCommand != null) {
                WorldCommandTargetBanner(state.activeCommand, callbacks)
            }
            WorldAssetReadinessBanner(readiness)
            WorldShortcutRow(state, callbacks, exitShortcutLabelRes)
            WorldCommandRow(state, callbacks)
            WorldPetStatusBar(state.hud.petStatus)
        }
    }
}

@Composable
private fun WorldHud(
    hud: WorldHudUi,
    callbacks: WorldUiCallbacks,
    homeActionLabelRes: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = hud.petName.ifBlank { stringResource(R.string.tama_world_pet_unknown) },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = hud.worldTime,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(hud.biome.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = { callbacks.onCommand(WorldUiCommand.OpenHome) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = stringResource(homeActionLabelRes)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = hud.currentGoal.ifBlank { stringResource(R.string.tama_world_goal_idle) },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = hud.currentAction,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (hud.currentGoalReason.isNotBlank()) {
                Text(
                    text = stringResource(R.string.tama_world_goal_reason, hud.currentGoalReason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (hud.nextNeed.isNotBlank()) {
                Text(
                    text = stringResource(R.string.tama_world_next_need, hud.nextNeed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun WorldCanvas(
    state: WorldUiState,
    callbacks: WorldUiCallbacks,
    atlas: WorldSpriteAtlas,
    modifier: Modifier
) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var lastTapMillis by remember { mutableLongStateOf(0L) }
    val tiles = remember(state.tiles) { state.tiles.associateBy { it.x to it.y } }
    val terrainMasks = remember(tiles) { tiles.mapValues { (_, tile) ->
        WorldBlobTiles.index(WorldBlobTiles.mask(tile, tiles) { it.terrainId == tile.terrainId })
    } }
    val terrainEdges = remember(tiles) { tiles.mapValues { (_, tile) -> WorldBlobTiles.overlays(tile, tiles) } }
    val farmTiles = remember(state.farmTiles) { state.farmTiles.filter(WorldFarmTileUi::hasCrop) }
    val actors = remember(state.actors) { state.actors.sortedBy { it.depth } }
    val structures = remember(state.structures) { state.structures.sortedBy { it.depth } }
    val resources = remember(state.resources) { state.resources.sortedBy { it.depth } }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val primary = MaterialTheme.colorScheme.primary
    val canvasDescription = stringResource(R.string.tama_world_canvas_description)
    val motion = remember(state.worldSeed) { WorldRenderMotion() }
    LaunchedEffect(state.actors, state.camera) {
        motion.update(state.actors, state.camera, SystemClock.uptimeMillis(),
            (1_000f / state.hud.simulationRateHz.coerceAtLeast(1f)).toLong())
    }
    var animationTimeNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(motion) {
        while (true) withFrameNanos { animationTimeNanos = it }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { viewportSize = it }
            .semantics {
                contentDescription = canvasDescription
            }
            .worldGestures(
                camera = state.camera,
                activeCommand = state.activeCommand,
                density = density,
                viewportSize = { viewportSize },
                onPan = { deltaX, deltaY ->
                    callbacks.onCommand(WorldUiCommand.PanCamera(deltaX, deltaY))
                },
                onZoom = { factor, focusX, focusY ->
                    callbacks.onCommand(WorldUiCommand.ZoomCamera(factor, focusX, focusY))
                },
                onTap = { screenPosition ->
                    val worldPosition = screenToWorld(screenPosition, viewportSize, motion.camera(state.camera, SystemClock.uptimeMillis()), density)
                    val target = findWorldTarget(state, worldPosition)
                    val doubleTap = SystemClock.uptimeMillis() - lastTapMillis <= WORLD_DOUBLE_TAP_WINDOW_MS
                    lastTapMillis = SystemClock.uptimeMillis()
                    if (state.activeCommand != null) {
                        val command = when (state.activeCommand) {
                            WorldPetCommandKind.GO_HERE -> WorldPetCommand.GoHere(
                                floor(worldPosition.x).toInt(),
                                floor(worldPosition.y).toInt()
                            )
                            WorldPetCommandKind.EXPLORE -> WorldPetCommand.Explore(
                                floor(worldPosition.x).toInt(),
                                floor(worldPosition.y).toInt()
                            )
                            WorldPetCommandKind.VISIT_NPC -> {
                                val actor = target as? WorldInspectTarget.Actor
                                actor?.let { WorldPetCommand.VisitNpc(it.id) }
                            }
                            else -> null
                        }
                        if (command != null) callbacks.onCommand(WorldUiCommand.IssuePetCommand(command))
                    } else if (doubleTap && target is WorldInspectTarget.Actor &&
                        state.actors.firstOrNull { it.id == target.id }?.isPet == true
                    ) {
                        callbacks.onCommand(WorldUiCommand.Recenter)
                    } else {
                        callbacks.onCommand(WorldUiCommand.Inspect(target))
                    }
                }
            )
    ) {
        val animationTimeMillis = animationTimeNanos / 1_000_000L
        val renderCamera = motion.camera(state.camera, animationTimeMillis)
        val tilePx = WORLD_TILE_LOGICAL_SIZE * density * renderCamera.zoom.coerceIn(0.5f, 4f)
        val center = Offset(size.width / 2f, size.height / 2f)
        val left = floor(renderCamera.centerX - size.width / (2f * tilePx)).toInt() - WORLD_CANVAS_MARGIN_TILES
        val right = ceil(renderCamera.centerX + size.width / (2f * tilePx)).toInt() + WORLD_CANVAS_MARGIN_TILES
        val top = floor(renderCamera.centerY - size.height / (2f * tilePx)).toInt() - WORLD_CANVAS_MARGIN_TILES
        val bottom = ceil(renderCamera.centerY + size.height / (2f * tilePx)).toInt() + WORLD_CANVAS_MARGIN_TILES

        drawRect(WorldCanvasOutline)
        for (y in top..bottom) {
            for (x in left..right) {
                val tile = tiles[x to y]
                val topLeft = Offset(
                    center.x + (x - renderCamera.centerX) * tilePx,
                    center.y + (y - renderCamera.centerY) * tilePx
                )
                val tileEntry = tile?.takeIf { it.known }?.let { atlas.entry(it.terrainId) }
                if (tileEntry != null) {
                    drawTerrainTile(
                        tileEntry,
                        topLeft,
                        tilePx,
                        terrainMasks.getValue(x to y),
                        animationTimeMillis
                    )
                    terrainEdges[x to y].orEmpty().forEach { (asset, mask) ->
                        atlas.entry(asset)?.let { overlay ->
                            drawTerrainTile(overlay, topLeft, tilePx, WorldBlobTiles.index(mask), animationTimeMillis)
                        }
                    }
                } else {
                    val color = tile?.let(::worldTileColor) ?: WorldCanvasFog
                    drawRect(color, topLeft, Size(tilePx + 1f, tilePx + 1f))
                }
                if (tile?.known == true) {
                    drawRect(WorldCanvasGrid, topLeft, Size(tilePx + 1f, tilePx + 1f), style = Stroke(0.6f))
                    if (tile.goalMarker) {
                        drawCircle(
                            color = primary.copy(alpha = 0.85f),
                            radius = tilePx * 0.18f,
                            center = topLeft + Offset(tilePx / 2f, tilePx / 2f)
                        )
                    }
                }
            }
        }

        val sprites = buildList {
            farmTiles.filter { farm ->
                isVisible(WorldPointUi(farm.x + 0.5f, farm.y + 1f), 1, 1, left, right, top, bottom)
            }.forEach { farm ->
                val assetId = farm.renderCropAssetId ?: return@forEach
                add(
                    WorldSpriteDraw(
                        depth = farm.y + 0.9f,
                        assetId = assetId,
                        position = WorldPointUi(farm.x + 0.5f, farm.y + 1f),
                        widthTiles = 1,
                        heightTiles = 1,
                        action = farm.cropStage.clipAction,
                        direction = WorldDirection.SOUTH,
                        frameIndex = 0,
                        selected = state.inspector?.targetId == "tile:${farm.x}:${farm.y}"
                    )
                )
            }
            resources.filter { isVisible(it.position, it.widthTiles, it.heightTiles, left, right, top, bottom) }
                .forEach { resource ->
                    add(
                        WorldSpriteDraw(
                            depth = resource.depth,
                            assetId = resource.assetId,
                            position = resource.position,
                            widthTiles = resource.widthTiles,
                            heightTiles = resource.heightTiles,
                            action = resource.state.id,
                            direction = WorldDirection.SOUTH,
                            frameIndex = 0,
                            selected = state.inspector?.targetId == resource.id
                        )
                    )
                }
            structures.filter { it.discovered && isVisible(it.position, it.widthTiles, it.heightTiles, left, right, top, bottom) }
                .forEach { structure ->
                    add(
                        WorldSpriteDraw(
                            depth = structure.depth,
                            assetId = structure.assetId,
                            position = structure.position,
                            widthTiles = structure.widthTiles,
                            heightTiles = structure.heightTiles,
                            action = "idle",
                            direction = WorldDirection.SOUTH,
                            frameIndex = 0,
                            selected = state.inspector?.targetId == structure.id
                        )
                    )
                }
            actors.filter { isVisible(it.position, 1, 1, left, right, top, bottom) }
                .forEach { actor ->
                    val actorPosition = motion.position(actor, animationTimeMillis)
                    add(
                        WorldSpriteDraw(
                            depth = actorPosition.y,
                            assetId = actor.assetId,
                            position = actorPosition,
                            widthTiles = 1,
                            heightTiles = 1,
                            action = actor.action,
                            direction = actor.direction,
                            frameIndex = actor.frameIndex,
                            selected = state.inspector?.targetId == actor.id,
                            actorId = actor.id,
                            toolAsset = worldToolAsset(actor.action),
                            effectAsset = worldActionEffect(actor.action,
                                tiles[floor(actor.position.x).toInt() to floor(actor.position.y - 0.01f).toInt()]?.water == true)
                        )
                    )
                }
        }.sortedBy(WorldSpriteDraw::depth)
        sprites.forEach { sprite ->
            drawWorldSprite(
                atlas = atlas,
                assetId = sprite.assetId,
                position = sprite.position,
                widthTiles = sprite.widthTiles,
                heightTiles = sprite.heightTiles,
                tilePx = tilePx,
                center = center,
                camera = renderCamera,
                action = sprite.action,
                direction = sprite.direction,
                frameIndex = sprite.frameIndex,
                animationTimeMillis = sprite.actorId?.let { motion.elapsed(it, animationTimeMillis) } ?: animationTimeMillis,
                selected = sprite.selected,
                actor = sprite.actorId != null,
                toolAsset = sprite.toolAsset,
                effectAsset = sprite.effectAsset
            )
        }
    }
}

@Composable
private fun Modifier.worldGestures(
    camera: WorldCameraUi,
    activeCommand: WorldPetCommandKind?,
    density: Float,
    viewportSize: () -> IntSize,
    onPan: (Float, Float) -> Unit,
    onZoom: (Float, Float, Float) -> Unit,
    onTap: (Offset) -> Unit
): Modifier {
    val currentCamera = rememberUpdatedState(camera)
    val currentDensity = rememberUpdatedState(density)
    val currentViewportSize = rememberUpdatedState(viewportSize)
    val currentOnPan = rememberUpdatedState(onPan)
    val currentOnZoom = rememberUpdatedState(onZoom)
    val currentOnTap = rememberUpdatedState(onTap)
    return this.pointerInput(activeCommand, density) {
        detectWorldGestures(
            onPan = { screenDelta ->
                val tilePx = WORLD_TILE_LOGICAL_SIZE * currentDensity.value *
                    currentCamera.value.zoom.coerceIn(0.5f, 4f)
                currentOnPan.value(-screenDelta.x / tilePx, -screenDelta.y / tilePx)
            },
            onZoom = { factor, focus ->
                val cameraState = currentCamera.value
                val position = screenToWorld(
                    focus,
                    currentViewportSize.value(),
                    cameraState,
                    currentDensity.value
                )
                currentOnZoom.value(factor, position.x, position.y)
            },
            onTap = { position -> currentOnTap.value(position) }
        )
    }
}

private suspend fun PointerInputScope.detectWorldGestures(
    onPan: (Offset) -> Unit,
    onZoom: (Float, Offset) -> Unit,
    onTap: (Offset) -> Unit
) {
    awaitEachGesture {
        // A parent scroll/click handler that already owns the down must not be
        // reinterpreted as a world inspection by this child surface.
        val firstDown = awaitFirstDown(requireUnconsumed = true)
        var previousCentroid = firstDown.position
        var previousSpan = 0f
        var previousPointerCount = 1
        var pendingPan = Offset.Zero
        var pendingZoom = 1f
        var moved = false
        var multiTouch = false

        while (true) {
            val event = awaitPointerEvent()
            // A parent may consume the stream after the down (for example when
            // the page scroll takes over). End it before the release can look
            // like a tap.
            if (event.changes.any { it.isConsumed }) break
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                if (!moved && !multiTouch && event.type == PointerEventType.Release) {
                    onTap(firstDown.position)
                }
                break
            }

            if (pressed.size > 1) multiTouch = true

            val centroid = Offset(
                pressed.map { it.position.x }.average().toFloat(),
                pressed.map { it.position.y }.average().toFloat()
            )
            val pan = centroid - previousCentroid
            val span = if (pressed.size > 1) {
                hypot(
                    (pressed[0].position.x - pressed[1].position.x).toDouble(),
                    (pressed[0].position.y - pressed[1].position.y).toDouble()
                ).toFloat()
            } else {
                0f
            }
            if (pressed.size != previousPointerCount) {
                // A second finger changes the centroid even when neither finger moved. Reset the
                // baseline so a pinch does not also pan the camera; do the same when one lifts.
                previousCentroid = centroid
                previousSpan = if (pressed.size > 1) span else 0f
                previousPointerCount = pressed.size
                pendingPan = Offset.Zero
                pendingZoom = 1f
                if (moved) event.changes.forEach { it.consume() }
                continue
            }
            val zoom = if (previousSpan > 1f && span > 1f) span / previousSpan else 1f
            pendingPan += pan
            if (previousSpan > 1f && span > 1f) pendingZoom *= zoom
            val touchSlopReached = pendingPan.getDistance() > viewConfiguration.touchSlop
            val zoomSlopReached = abs(pendingZoom - 1f) > WORLD_GESTURE_ZOOM_SLOP
            if (!moved && (touchSlopReached || zoomSlopReached)) {
                moved = true
                if (pendingPan.getDistance() > 0f) onPan(pendingPan)
                if (abs(pendingZoom - 1f) > 0.0001f) onZoom(pendingZoom, centroid)
                pendingPan = Offset.Zero
                pendingZoom = 1f
                event.changes.forEach { it.consume() }
            } else if (moved) {
                // Once touch slop has been crossed, preserve every small delta
                // instead of dropping slow drags or fine-grained pinch motion.
                if (pan.getDistance() > 0f) onPan(pan)
                if (abs(zoom - 1f) > 0.0001f) onZoom(zoom, centroid)
                if (pan.getDistance() > 0f || abs(zoom - 1f) > 0.0001f) {
                    event.changes.forEach { it.consume() }
                }
            }
            previousCentroid = centroid
            if (span > 1f) previousSpan = span
        }
    }
}

private fun screenToWorld(
    screen: Offset,
    viewport: IntSize,
    camera: WorldCameraUi,
    density: Float
): WorldPointUi {
    if (viewport.width <= 0 || viewport.height <= 0) {
        return WorldPointUi(camera.centerX, camera.centerY)
    }
    val tilePx = WORLD_TILE_LOGICAL_SIZE * density * camera.zoom.coerceIn(0.5f, 4f)
    return WorldPointUi(
        x = camera.centerX + (screen.x - viewport.width / 2f) / tilePx,
        y = camera.centerY + (screen.y - viewport.height / 2f) / tilePx
    )
}

private fun findWorldTarget(state: WorldUiState, world: WorldPointUi): WorldInspectTarget {
    val actor = state.actors.minByOrNull { actor ->
        hypot((actor.position.x - world.x).toDouble(), (actor.position.y - world.y).toDouble())
    }
    if (actor != null && distance(actor.position, world) <= 0.8f) return WorldInspectTarget.Actor(actor.id)

    val resource = state.resources.firstOrNull { resource ->
        world.x >= resource.position.x - resource.widthTiles / 2f &&
            world.x <= resource.position.x + resource.widthTiles / 2f &&
            world.y >= resource.position.y - resource.heightTiles &&
            world.y <= resource.position.y
    }
    if (resource != null) return WorldInspectTarget.Resource(resource.id)

    val structure = state.structures.firstOrNull { structure ->
        structure.discovered &&
            world.x >= structure.position.x - structure.widthTiles / 2f &&
            world.x <= structure.position.x + structure.widthTiles / 2f &&
            world.y >= structure.position.y - structure.heightTiles && world.y <= structure.position.y
    }
    if (structure != null) return WorldInspectTarget.Structure(structure.id)
    return WorldInspectTarget.Tile(floor(world.x).toInt(), floor(world.y).toInt())
}

private fun distance(first: WorldPointUi, second: WorldPointUi): Float = hypot(
    (first.x - second.x).toDouble(),
    (first.y - second.y).toDouble()
).toFloat()

private fun isVisible(
    position: WorldPointUi,
    widthTiles: Int,
    heightTiles: Int,
    left: Int,
    right: Int,
    top: Int,
    bottom: Int
): Boolean = position.x + widthTiles >= left && position.x <= right &&
    position.y >= top && position.y - heightTiles <= bottom

private fun DrawScope.drawWorldSprite(
    atlas: WorldSpriteAtlas,
    assetId: String,
    position: WorldPointUi,
    widthTiles: Int,
    heightTiles: Int,
    tilePx: Float,
    center: Offset,
    camera: WorldCameraUi,
    action: String,
    direction: WorldDirection,
    frameIndex: Int,
    animationTimeMillis: Long,
    selected: Boolean,
    actor: Boolean = false,
    toolAsset: String? = null,
    effectAsset: String? = null
) {
    val screenX = center.x + (position.x - camera.centerX) * tilePx
    val screenY = center.y + (position.y - camera.centerY) * tilePx
    val atlasEntry = atlas.entry(assetId)
    val definition = atlasEntry?.definition
    val renderWidthTiles = definition?.visualWidthTiles?.coerceAtLeast(1)
        ?: if (widthTiles == 1 && definition != null) {
            max(1, ceil(definition.frameWidth / WORLD_TILE_LOGICAL_SIZE).toInt())
        } else {
            widthTiles.coerceAtLeast(1)
        }
    val renderHeightTiles = definition?.visualHeightTiles?.coerceAtLeast(1)
        ?: if (heightTiles == 1 && definition != null) {
            max(1, ceil(definition.frameHeight / WORLD_TILE_LOGICAL_SIZE).toInt())
        } else {
            heightTiles.coerceAtLeast(1)
        }
    val anchorX = definition?.let { anchorFraction(it.footAnchor.x, it.frameWidth) } ?: 0.5f
    val anchorY = definition?.let { anchorFraction(it.footAnchor.y, it.frameHeight) } ?: 1f
    val destination = IntOffset(
        (screenX - renderWidthTiles * tilePx * anchorX).roundToInt(),
        (screenY - renderHeightTiles * tilePx * anchorY).roundToInt()
    )
    val destinationSize = IntSize(
        max(1, (renderWidthTiles * tilePx).roundToInt()),
        max(1, (renderHeightTiles * tilePx).roundToInt())
    )
    val clip = atlasEntry?.clip(action, direction) ?: atlasEntry?.definition?.clips?.firstOrNull()
    val index = clip?.frames?.takeIf { it.isNotEmpty() }?.let { frames ->
        val animationFrame = if (assetId.endsWith("_egg")) {
            if (frames.size > 1 && animationTimeMillis % 2_400L >= 2_180L) 1 else 0
        } else if (clip.frameMs > 0) {
            ((animationTimeMillis / clip.frameMs) % frames.size).toInt()
        } else {
            0
        }
        frames[(frameIndex.coerceAtLeast(0) + animationFrame) % frames.size]
    } ?: frameIndex.coerceAtLeast(0)
    val toolEntry = if (actor) toolAsset?.let(atlas::entry) else null
    val toolBehindActor = toolEntry?.definition?.directionTransform(direction)?.behindActor == true
    fun DrawScope.drawToolOverlay() {
        val hand = definition?.toolAnchors?.get(index.toString()) ?: return
        drawWorldTool(
            entry = toolEntry,
            hand = destination.toOffset() + Offset(hand.x * tilePx / WORLD_TILE_LOGICAL_SIZE,
                hand.y * tilePx / WORLD_TILE_LOGICAL_SIZE),
            tilePx = tilePx,
            elapsedMs = animationTimeMillis,
            direction = direction
        )
    }
    if (actor) {
        drawWorldOverlay(atlas.entry("actor_shadow"), Offset(screenX, screenY + tilePx * 0.06f), tilePx, 0L)
        if (toolBehindActor) drawToolOverlay()
    }
    if (atlasEntry == null || clip == null) {
        drawRect(
            color = if (selected) Color(0xFFFFD166) else Color(0xFFE98B6D),
            topLeft = destination.toOffset(),
            size = Size(destinationSize.width.toFloat(), destinationSize.height.toFloat())
        )
    } else {
        val sourceX = (index % atlasEntry.definition.columns) * atlasEntry.definition.frameWidth
        val sourceY = (index / atlasEntry.definition.columns) * atlasEntry.definition.frameHeight
        drawImage(
            image = atlasEntry.image,
            srcOffset = IntOffset(sourceX, sourceY),
            srcSize = IntSize(atlasEntry.definition.frameWidth, atlasEntry.definition.frameHeight),
            dstOffset = destination,
            dstSize = destinationSize,
            filterQuality = FilterQuality.None
        )
    }
    if (actor && !toolBehindActor) drawToolOverlay()
    if (effectAsset != null) {
        val raised = effectAsset in setOf("fx_social_heart", "fx_question", "fx_thought", "fx_level_success")
        drawWorldOverlay(atlas.entry(effectAsset), Offset(screenX, screenY - if (raised) tilePx * 1.5f else 0f),
            tilePx, animationTimeMillis)
    }
    if (selected) {
        drawCircle(
            color = Color(0xFFFFD166),
            radius = min(destinationSize.width, destinationSize.height) * 0.48f,
            center = Offset(screenX, screenY),
            style = Stroke(width = max(1f, tilePx * 0.06f))
        )
    }
}

private fun anchorFraction(value: Float, frameSize: Int): Float =
    if (value in 0f..1f) value else (value / frameSize.coerceAtLeast(1)).coerceIn(0f, 1f)

private fun DrawScope.drawTerrainTile(
    entry: WorldSpriteAtlasEntry,
    topLeft: Offset,
    tilePx: Float,
    maskIndex: Int,
    animationTimeMillis: Long
) {
    val clip = entry.clip("flow", WorldDirection.SOUTH) ?: entry.clip("default", WorldDirection.SOUTH)
        ?: entry.definition.clips.firstOrNull()
    val frame = clip?.frames?.takeIf { it.isNotEmpty() }?.let { frames ->
        val animationFrame = if (clip.frameMs > 0) {
            ((animationTimeMillis / clip.frameMs) % frames.size).toInt()
        } else {
            0
        }
        frames[animationFrame % frames.size]
    } ?: 0
    val cell = frame + maskIndex
    val sourceX = (cell % entry.definition.columns) * entry.definition.frameWidth
    val sourceY = (cell / entry.definition.columns) * entry.definition.frameHeight
    drawImage(
        image = entry.image,
        srcOffset = IntOffset(sourceX, sourceY),
        srcSize = IntSize(entry.definition.frameWidth, entry.definition.frameHeight),
        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
        dstSize = IntSize(max(1, tilePx.roundToInt()), max(1, tilePx.roundToInt())),
        filterQuality = FilterQuality.None
    )
}

private fun IntOffset.toOffset(): Offset = Offset(x.toFloat(), y.toFloat())

private fun worldTileColor(tile: WorldTileUi): Color {
    tile.tintArgb?.let { return Color(it) }
    if (!tile.known) return WorldCanvasFog
    val terrain = tile.terrainId.lowercase()
    return when {
        tile.water || terrain.contains("water") -> WorldCanvasWater
        terrain.contains("sand") -> WorldCanvasSand
        terrain.contains("rock") || terrain.contains("stone") -> WorldCanvasRock
        terrain.contains("snow") || terrain.contains("ice") -> WorldCanvasSnow
        terrain.contains("farmland_wet") -> WorldCanvasWetSoil
        terrain.contains("farmland_dry") -> WorldCanvasDrySoil
        terrain.contains("dirt") || terrain.contains("path") || terrain.contains("farm") || terrain.contains("mud") -> WorldCanvasDirt
        terrain.contains("mystic") -> WorldCanvasMystic
        tile.biome == WorldBiome.FOREST -> WorldCanvasForest
        else -> WorldCanvasGrass
    }
}

@Composable
private fun WorldMinimap(
    minimap: WorldMiniMapUi,
    atlas: WorldSpriteAtlas,
    modifier: Modifier = Modifier
) {
    val minimapDescription = stringResource(R.string.tama_world_minimap_description)
    Card(
        modifier = modifier.size(WORLD_MINIMAP_SIZE_DP.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xE62A3038)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .semantics { contentDescription = minimapDescription }
        ) {
            if (minimap.widthTiles <= 0 || minimap.heightTiles <= 0) return@Canvas
            val cellWidth = size.width / minimap.widthTiles.toFloat()
            val cellHeight = size.height / minimap.heightTiles.toFloat()
            minimap.tiles.filter { !minimap.knownOnly || it.known }.forEach { tile ->
                drawRect(
                    color = minimapTileColor(tile),
                    topLeft = Offset(tile.x * cellWidth, tile.y * cellHeight),
                    size = Size(
                        cellWidth * tile.spanTiles + 0.5f,
                        cellHeight * tile.spanTiles + 0.5f
                    )
                )
            }
            // Facilities underneath actor dots keep the pet readable in a dense settlement.
            minimap.markers.sortedBy { if (it.kind == "structure") 0 else 1 }.forEach { marker ->
                val center = Offset(
                    (marker.x + 0.5f) * cellWidth,
                    (marker.y + 0.5f) * cellHeight
                )
                val entry = marker.assetId?.let(atlas::entry)
                if (entry != null) {
                    val iconSize = 11.dp.toPx().roundToInt().coerceAtLeast(1)
                    val definition = entry.definition
                    val frame = definition.clips.first().frames.first()
                    drawImage(entry.image,
                        srcOffset = IntOffset(frame % definition.columns * definition.frameWidth,
                            frame / definition.columns * definition.frameHeight),
                        srcSize = IntSize(definition.frameWidth, definition.frameHeight),
                        dstOffset = IntOffset((center.x - iconSize / 2f).roundToInt(), (center.y - iconSize / 2f).roundToInt()),
                        dstSize = IntSize(iconSize, iconSize), filterQuality = FilterQuality.None)
                }
                if (entry == null || marker.selected) drawCircle(
                    color = if (marker.selected) Color(0xFFFFD166) else Color.White,
                    radius = if (entry != null) 6.dp.toPx() else max(1.4f, min(cellWidth, cellHeight) * 0.8f),
                    center = center,
                    style = if (entry != null) Stroke(1.dp.toPx()) else Fill
                )
            }
        }
    }
}

private fun minimapTileColor(tile: WorldMiniMapTileUi): Color = when {
    tile.terrainId.contains("water", ignoreCase = true) -> WorldCanvasWater
    tile.terrainId.contains("sand", ignoreCase = true) -> WorldCanvasSand
    tile.terrainId.contains("rock", ignoreCase = true) -> WorldCanvasRock
    tile.terrainId.contains("snow", ignoreCase = true) || tile.terrainId.contains("ice", ignoreCase = true) -> WorldCanvasSnow
    tile.biome == WorldBiome.FOREST -> WorldCanvasForest
    tile.biome == WorldBiome.MYSTIC_GROVE -> WorldCanvasMystic
    else -> WorldCanvasGrass
}

@Composable
private fun WorldInspectorCard(
    inspector: WorldInspectorUi,
    callbacks: WorldUiCallbacks,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = WORLD_MAX_INSPECTOR_HEIGHT_DP.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = inspector.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = inspector.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { callbacks.onCommand(WorldUiCommand.DismissInspector) }) {
                    Icon(Icons.Default.Close, stringResource(R.string.tama_world_close_inspector))
                }
            }
            inspector.fields.forEach { field ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = field.label,
                        modifier = Modifier.weight(0.9f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = field.value,
                        modifier = Modifier.weight(1.1f),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (field.emphasize) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (inspector.actions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    inspector.actions.forEach { action ->
                        OutlinedButton(
                            onClick = {
                                callbacks.onCommand(WorldUiCommand.InspectorAction(inspector.targetId, action.id))
                            },
                            enabled = action.enabled,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(action.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldAssetReadinessBanner(readiness: WorldAssetReadinessUi) {
    if (readiness.isReady && !readiness.isLoading) return
    val message = when {
        readiness.isLoading -> stringResource(R.string.tama_world_assets_loading)
        // Loader diagnostics are internal English/error-class text. Translate
        // known failures before they reach the user-facing format string.
        readiness.error != null -> {
            val reason = when (readiness.error) {
                "Manifest could not be read" -> stringResource(R.string.tama_world_assets_manifest_unreadable)
                "Unsupported manifest schema" -> stringResource(R.string.tama_world_assets_manifest_schema)
                else -> stringResource(R.string.tama_world_assets_reason_generic)
            }
            stringResource(R.string.tama_world_assets_error, reason)
        }
        readiness.missingAssetIds.isNotEmpty() -> stringResource(
            R.string.tama_world_assets_missing,
            readiness.missingAssetIds.size
        )
        readiness.invalidAssetIds.isNotEmpty() -> stringResource(
            R.string.tama_world_assets_invalid,
            readiness.invalidAssetIds.size
        )
        else -> stringResource(R.string.tama_world_assets_incomplete)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WorldShortcutRow(
    state: WorldUiState,
    callbacks: WorldUiCallbacks,
    exitShortcutLabelRes: Int?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        exitShortcutLabelRes?.let { labelRes ->
            AssistChip(
                onClick = { callbacks.onCommand(WorldUiCommand.CloseWorld) },
                label = { Text(stringResource(labelRes), maxLines = 1) },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(WORLD_HOME_EXIT_TEST_TAG)
            )
        }
        AssistChip(
            onClick = { callbacks.onCommand(WorldUiCommand.OpenHome) },
            label = { Text(stringResource(R.string.tama_world_shortcut_home), maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Home, null, Modifier.size(18.dp)) }
        )
        AssistChip(
            onClick = { callbacks.onCommand(WorldUiCommand.OpenWorld) },
            label = { Text(stringResource(R.string.tama_world_shortcut_world), maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Explore, null, Modifier.size(18.dp)) }
        )
        AssistChip(
            onClick = { callbacks.onCommand(WorldUiCommand.OpenInventory) },
            label = { Text(stringResource(R.string.tama_world_shortcut_inventory), maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Backpack, null, Modifier.size(18.dp)) }
        )
        AssistChip(
            onClick = { callbacks.onCommand(WorldUiCommand.OpenJournal) },
            label = { Text(stringResource(R.string.tama_world_shortcut_journal), maxLines = 1) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp)) }
        )
        AssistChip(
            onClick = { callbacks.onCommand(WorldUiCommand.OpenBrainTraining) },
            label = { Text(stringResource(R.string.tama_world_shortcut_brain), maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Psychology, null, Modifier.size(18.dp)) }
        )
        AssistChip(
            onClick = { callbacks.onCommand(WorldUiCommand.ToggleMinimap) },
            label = { Text(stringResource(R.string.tama_world_shortcut_map), maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Explore, null, Modifier.size(18.dp)) }
        )
        val petId = state.actors.firstOrNull { it.isPet }?.id
        if (petId != null) {
            AssistChip(
                onClick = {
                    callbacks.onCommand(
                        WorldUiCommand.SetCameraMode(WorldCameraMode.FOLLOW_PET, petId)
                    )
                },
                label = { Text(stringResource(R.string.tama_world_camera_follow_pet), maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.CenterFocusStrong, null, Modifier.size(18.dp)) }
            )
        }
        AssistChip(
            onClick = { callbacks.onCommand(WorldUiCommand.SetCameraMode(WorldCameraMode.FREE)) },
            label = { Text(stringResource(R.string.tama_world_camera_free), maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) }
        )
    }
}

const val WORLD_HOME_EXIT_TEST_TAG = "tama_world_home_exit"

@Composable
private fun WorldCommandRow(
    state: WorldUiState,
    callbacks: WorldUiCallbacks
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val selectedNpc = state.inspector as? WorldInspectorUi.Npc
        if (selectedNpc != null) {
            WorldCommandButton(WorldPetCommandKind.VISIT_NPC, Icons.Default.CenterFocusStrong) {
                callbacks.onCommand(
                    WorldUiCommand.SetCameraMode(WorldCameraMode.FOLLOW_NPC, selectedNpc.actor.id)
                )
            }
        }
        WorldCommandButton(WorldPetCommandKind.COME_HOME, Icons.Default.Home) {
            callbacks.onCommand(WorldUiCommand.IssuePetCommand(WorldPetCommand.ComeHome))
        }
        WorldCommandButton(WorldPetCommandKind.GO_HERE, Icons.Default.CenterFocusStrong) {
            callbacks.onCommand(WorldUiCommand.BeginTargetedCommand(WorldPetCommandKind.GO_HERE))
        }
        WorldCommandButton(WorldPetCommandKind.VISIT_NPC, Icons.Default.Explore) {
            callbacks.onCommand(WorldUiCommand.BeginTargetedCommand(WorldPetCommandKind.VISIT_NPC))
        }
        WorldCommandButton(WorldPetCommandKind.EXPLORE, Icons.Default.Explore) {
            callbacks.onCommand(WorldUiCommand.BeginTargetedCommand(WorldPetCommandKind.EXPLORE))
        }
        WorldCommandButton(WorldPetCommandKind.REST, Icons.Default.Tune) {
            callbacks.onCommand(WorldUiCommand.IssuePetCommand(WorldPetCommand.Rest))
        }
        WorldCommandButton(WorldPetCommandKind.STOP, Icons.Default.Stop) {
            callbacks.onCommand(WorldUiCommand.IssuePetCommand(WorldPetCommand.Stop))
        }
        OutlinedButton(
            onClick = { callbacks.onCommand(WorldUiCommand.Recenter) },
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.tama_world_recenter), maxLines = 1)
        }
    }
}

@Composable
private fun WorldCommandButton(
    kind: WorldPetCommandKind,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(kind.labelRes), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WorldCommandTargetBanner(
    command: WorldPetCommandKind,
    callbacks: WorldUiCallbacks
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.tama_world_command_target_hint, stringResource(command.labelRes)),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { callbacks.onCommand(WorldUiCommand.CancelTargetedCommand) }) {
                Text(stringResource(R.string.tama_world_cancel))
            }
        }
    }
}

@Composable
private fun WorldPetStatusBar(status: WorldPetStatusUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(stringResource(R.string.tama_world_stat_hunger), status.hunger ?: 0, Modifier.weight(1f))
            StatusPill(stringResource(R.string.tama_world_stat_hydration), status.hydration ?: 0, Modifier.weight(1f))
            StatusPill(stringResource(R.string.tama_world_stat_energy), status.energy ?: 0, Modifier.weight(1f))
            StatusPill(stringResource(R.string.tama_world_stat_health), status.health, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(stringResource(R.string.tama_world_stat_hygiene), status.hygiene ?: 0, Modifier.weight(1f))
            StatusPill(stringResource(R.string.tama_world_stat_happiness), status.happiness, Modifier.weight(1f))
            StatusPill(stringResource(R.string.tama_world_stat_social), status.social, Modifier.weight(1f))
            StatusPill(stringResource(R.string.tama_world_stat_curiosity), status.curiosity ?: 0, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatusPill(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 34.dp),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = "${value.coerceIn(0, 100)}%",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1
            )
        }
    }
}
