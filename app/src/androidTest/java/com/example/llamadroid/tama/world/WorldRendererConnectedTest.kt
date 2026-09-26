package com.example.llamadroid.tama.world

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.Direction
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.WorldGenerator
import com.example.llamadroid.tama.world.core.WorldKnowledge
import com.example.llamadroid.tama.world.persistence.WorldBuildingLayouts
import com.example.llamadroid.tama.world.presentation.WorldUiViewport
import com.example.llamadroid.tama.world.presentation.projectWorldState
import com.example.llamadroid.tama.world.ui.WorldAssetCatalog
import com.example.llamadroid.tama.world.ui.WorldAssetManifestLoader
import com.example.llamadroid.tama.world.ui.WorldCameraMode
import com.example.llamadroid.tama.world.ui.WorldCameraUi
import com.example.llamadroid.tama.world.ui.WorldScreen
import com.example.llamadroid.tama.world.ui.WorldUiCallbacks
import com.example.llamadroid.tama.world.ui.WorldUiCommand
import com.example.llamadroid.ui.theme.LlamaDroidTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real renderer/gesture probe with an isolated immutable world; never opens a living DAO. */
@RunWith(AndroidJUnit4::class)
class WorldRendererConnectedTest {
    @Test fun productionAtlasRendersAtThirtyFramesAndAcceptsCameraGestures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val generated = WorldGenerator.generate(1219L, structureLayouts = WorldBuildingLayouts.read(context))
        val world = WorldKnowledge.observe(generated.copy(actor = generated.actor.copy(
            presence = PresenceMode.WORLD, structureId = null, action = ActionId.WALK, facing = Direction.EAST
        )), 22, generated.lastSimulatedAt)
        val ui = projectWorldState(world, viewport = WorldUiViewport(30, 22), petStage = "baby")
        val visible = buildSet {
            addAll(ui.tiles.filter { it.known }.map { it.terrainId })
            addAll(ui.actors.map { it.assetId })
            addAll(ui.structures.map { it.assetId })
            addAll(ui.resources.map { it.assetId })
            addAll(ui.minimap?.markers.orEmpty().mapNotNull { it.assetId })
            addAll(listOf("actor_shadow", "fx_dust_walk", "edge_grass_dirt", "edge_water_shore"))
        }
        val loaded = runBlocking {
            WorldAssetManifestLoader(context.assets).load(expectedAssetIds = WorldAssetCatalog.requiredAssetIds,
                visibleAssetIds = visible, allowDevelopmentFallbacks = false)
        }
        assertTrue("Required production assets missing: ${loaded.readiness.missingAssetIds}", loaded.readiness.missingAssetIds.isEmpty())
        assertTrue("Invalid production assets: ${loaded.readiness.invalidAssetIds}", loaded.readiness.invalidAssetIds.isEmpty())

        val frames = AtomicLong()
        val slowFrames = AtomicLong()
        val commands = CopyOnWriteArrayList<WorldUiCommand>()
        val latestCamera = AtomicReference(ui.camera)
        val callbackThread = HandlerThread("WorldFrameProbe").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames.incrementAndGet()
            if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > 33_333_333L) slowFrames.incrementAndGet()
        }
        val intent = Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<ComponentActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addOnFrameMetricsAvailableListener(listener, Handler(callbackThread.looper))
                    activity.setContent {
                        var snapshot by remember { mutableStateOf(ui) }
                        LaunchedEffect(Unit) {
                            var tick = 0
                            while (true) {
                                delay(100)
                                tick++
                                snapshot = snapshot.copy(actors = snapshot.actors.map { actor ->
                                    if (!actor.isPet) actor else actor.copy(position = actor.position.copy(
                                        x = actor.position.x + sin(tick / 10.0).toFloat()
                                    ))
                                })
                            }
                        }
                        LlamaDroidTheme(dynamicColor = false) {
                            WorldScreen(snapshot, WorldUiCallbacks { command ->
                                commands.add(command)
                                when (command) {
                                    is WorldUiCommand.PanCamera -> {
                                        val next = snapshot.camera.copy(
                                            centerX = snapshot.camera.centerX + command.deltaX,
                                            centerY = snapshot.camera.centerY + command.deltaY,
                                            mode = WorldCameraMode.FREE
                                        )
                                        snapshot = snapshot.copy(camera = next)
                                        latestCamera.set(next)
                                    }
                                    is WorldUiCommand.ZoomCamera -> {
                                        val next = applyZoom(snapshot.camera, command)
                                        snapshot = snapshot.copy(camera = next)
                                        latestCamera.set(next)
                                    }
                                    else -> Unit
                                }
                            },
                                spriteAtlas = loaded.atlas, loadAssets = false)
                        }
                    }
                }
                // Warm shader/font caches before counting presented frames.
                Thread.sleep(2_000L)
                frames.set(0)
                slowFrames.set(0)
                val started = SystemClock.elapsedRealtime()
                Thread.sleep(5_000L)
                val elapsed = SystemClock.elapsedRealtime() - started
                val measuredFrames = frames.get()
                val fps = measuredFrames * 1_000.0 / elapsed
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val output = File(context.filesDir, "world-render-qa").apply { mkdirs() }
                File(output, "world.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(output, "metrics.json").writeText(JSONObject().apply {
                    put("seed", 1219L)
                    put("frames", measuredFrames)
                    put("elapsedMs", elapsed)
                    put("fps", fps)
                    put("framesOver33ms", slowFrames.get())
                    put("loadedAtlasEntries", loaded.atlas.entries.size)
                    put("fixture", "isolated_world_renderer_10hz_snapshots")
                }.toString(2))
                val x = screenshot.width * 0.5f
                val y = screenshot.height * 0.35f
                val tapStarted = SystemClock.uptimeMillis()
                injectTouch(MotionEvent.ACTION_DOWN, x, y, tapStarted)
                injectTouch(MotionEvent.ACTION_UP, x, y, tapStarted)
                Thread.sleep(200)
                assertTrue("World canvas did not emit an inspection intent", commands.any { it is WorldUiCommand.Inspect })
                val dragStarted = SystemClock.uptimeMillis()
                val dragX = screenshot.width * 0.75f
                injectTouch(MotionEvent.ACTION_DOWN, dragX, y, dragStarted)
                repeat(20) { step ->
                    Thread.sleep(16)
                    injectTouch(MotionEvent.ACTION_MOVE, dragX - (step + 1) * 24f, y, dragStarted)
                }
                injectTouch(MotionEvent.ACTION_UP, dragX - 480f, y, dragStarted)
                Thread.sleep(200)
                val panDistance = commands.filterIsInstance<WorldUiCommand.PanCamera>()
                    .sumOf { abs(it.deltaX).toDouble() }
                val panCommands = commands.filterIsInstance<WorldUiCommand.PanCamera>()
                assertTrue("World canvas pan covered only $panDistance tiles", panDistance > 2.5)
                assertTrue("Sustained drag emitted only ${panCommands.size} pan update(s)", panCommands.size >= 2)
                assertTrue(
                    "World camera moved only ${abs(latestCamera.get().centerX - ui.camera.centerX)} tiles",
                    abs(latestCamera.get().centerX - ui.camera.centerX) > 2.5f
                )
                assertEquals(
                    ui.camera.centerX + panCommands.sumOf { it.deltaX.toDouble() }.toFloat(),
                    latestCamera.get().centerX,
                    0.08f
                )

                val pinchStarted = SystemClock.uptimeMillis()
                val pinchStartCamera = latestCamera.get()
                val pinchY = screenshot.height * 0.45f
                val firstPinchX = screenshot.width * 0.40f
                val secondPinchX = screenshot.width * 0.60f
                injectTouch(MotionEvent.ACTION_DOWN, firstPinchX, pinchY, pinchStarted)
                injectMultiTouch(
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    firstPinchX,
                    pinchY,
                    secondPinchX,
                    pinchY,
                    pinchStarted
                )
                val zoomStartCommandIndex = commands.size
                repeat(10) { step ->
                    Thread.sleep(16)
                    val spread = screenshot.width * 0.002f * (step + 1)
                    injectMultiTouch(
                        MotionEvent.ACTION_MOVE,
                        firstPinchX - spread,
                        pinchY,
                        secondPinchX + spread,
                        pinchY,
                        pinchStarted
                    )
                }
                val finalSpread = screenshot.width * 0.002f * 10
                injectMultiTouch(
                    MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    firstPinchX - finalSpread,
                    pinchY,
                    secondPinchX + finalSpread,
                    pinchY,
                    pinchStarted
                )
                injectTouch(MotionEvent.ACTION_UP, firstPinchX - finalSpread, pinchY, pinchStarted)
                Thread.sleep(200)
                val zoomCommands = commands
                    .drop(zoomStartCommandIndex)
                    .filterIsInstance<WorldUiCommand.ZoomCamera>()
                assertTrue("World canvas did not emit a sustained zoom intent", zoomCommands.isNotEmpty())
                val cumulativeFactor = zoomCommands.fold(1f) { product, command -> product * command.factor }
                assertTrue("Pinch zoom accumulated only $cumulativeFactor", cumulativeFactor > 1.15f)
                val expectedPinchCamera = zoomCommands.fold(pinchStartCamera, ::applyZoom)
                assertTrue(
                    "World camera zoom changed only to ${latestCamera.get().zoom}",
                    latestCamera.get().zoom > pinchStartCamera.zoom + 0.1f
                )
                assertEquals(expectedPinchCamera.centerX, latestCamera.get().centerX, 0.08f)
                assertEquals(expectedPinchCamera.centerY, latestCamera.get().centerY, 0.08f)
                assertEquals(expectedPinchCamera.zoom, latestCamera.get().zoom, 0.02f)
                assertTrue("Renderer averaged $fps FPS ($measuredFrames frames/$elapsed ms)", fps >= 30.0)
                screenshot.recycle()
                scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            }
        } finally {
            callbackThread.quitSafely()
            callbackThread.join(2_000L)
        }
    }

    @Test fun isolatedCanvasCameraGesturesAccumulateWithoutAssetGate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val generated = WorldGenerator.generate(1221L)
        val world = WorldKnowledge.observe(
            generated.copy(actor = generated.actor.copy(
                presence = PresenceMode.WORLD,
                structureId = null,
                action = ActionId.WALK,
                facing = Direction.EAST
            )),
            22,
            generated.lastSimulatedAt
        )
        val initial = projectWorldState(world, viewport = WorldUiViewport(30, 22), petStage = "baby")
        val commands = CopyOnWriteArrayList<WorldUiCommand>()
        val latestCamera = AtomicReference(initial.camera)
        val intent = Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ComponentActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    var snapshot by remember { mutableStateOf(initial) }
                    LlamaDroidTheme(dynamicColor = false) {
                        WorldScreen(
                            snapshot,
                            WorldUiCallbacks { command ->
                                commands.add(command)
                                when (command) {
                                    is WorldUiCommand.PanCamera -> {
                                        val next = snapshot.camera.copy(
                                            centerX = snapshot.camera.centerX + command.deltaX,
                                            centerY = snapshot.camera.centerY + command.deltaY,
                                            mode = WorldCameraMode.FREE
                                        )
                                        snapshot = snapshot.copy(camera = next)
                                        latestCamera.set(next)
                                    }
                                    is WorldUiCommand.ZoomCamera -> {
                                        val next = applyZoom(snapshot.camera, command)
                                        snapshot = snapshot.copy(camera = next)
                                        latestCamera.set(next)
                                    }
                                    else -> Unit
                                }
                            },
                            loadAssets = false
                        )
                    }
                }
            }
            Thread.sleep(350L)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val width = screenshot.width.toFloat()
                val height = screenshot.height.toFloat()
                val y = height * 0.35f
                val dragStarted = SystemClock.uptimeMillis()
                val dragX = width * 0.75f
                injectTouch(MotionEvent.ACTION_DOWN, dragX, y, dragStarted)
                repeat(12) { step ->
                    Thread.sleep(16L)
                    injectTouch(MotionEvent.ACTION_MOVE, dragX - (step + 1) * 20f, y, dragStarted)
                }
                injectTouch(MotionEvent.ACTION_UP, dragX - 240f, y, dragStarted)
                Thread.sleep(150L)
                val panCommands = commands.filterIsInstance<WorldUiCommand.PanCamera>()
                val panDistance = panCommands
                    .sumOf { abs(it.deltaX).toDouble() }
                assertTrue("Isolated canvas pan covered only $panDistance tiles", panDistance > 1.0)
                assertTrue("Isolated sustained drag emitted only ${panCommands.size} pan update(s)", panCommands.size >= 2)
                assertEquals(
                    initial.camera.centerX + panCommands.sumOf { it.deltaX.toDouble() }.toFloat(),
                    latestCamera.get().centerX,
                    0.08f
                )

                val pinchStartCamera = latestCamera.get()
                val pinchStarted = SystemClock.uptimeMillis()
                val pinchY = height * 0.45f
                val firstPinchX = width * 0.40f
                val secondPinchX = width * 0.60f
                injectTouch(MotionEvent.ACTION_DOWN, firstPinchX, pinchY, pinchStarted)
                injectMultiTouch(
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    firstPinchX,
                    pinchY,
                    secondPinchX,
                    pinchY,
                    pinchStarted
                )
                val zoomStartCommandIndex = commands.size
                repeat(10) { step ->
                    Thread.sleep(16L)
                    val spread = width * 0.002f * (step + 1)
                    injectMultiTouch(
                        MotionEvent.ACTION_MOVE,
                        firstPinchX - spread,
                        pinchY,
                        secondPinchX + spread,
                        pinchY,
                        pinchStarted
                    )
                }
                val finalSpread = width * 0.002f * 10
                injectMultiTouch(
                    MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    firstPinchX - finalSpread,
                    pinchY,
                    secondPinchX + finalSpread,
                    pinchY,
                    pinchStarted
                )
                injectTouch(MotionEvent.ACTION_UP, firstPinchX - finalSpread, pinchY, pinchStarted)
                Thread.sleep(150L)
                val zoomCommands = commands
                    .drop(zoomStartCommandIndex)
                    .filterIsInstance<WorldUiCommand.ZoomCamera>()
                assertTrue("Isolated canvas did not emit a zoom intent", zoomCommands.isNotEmpty())
                val cumulativeFactor = zoomCommands.fold(1f) { product, command -> product * command.factor }
                assertTrue("Isolated pinch zoom accumulated only $cumulativeFactor", cumulativeFactor > 1.15f)
                val finalCamera = latestCamera.get()
                assertTrue("Isolated canvas zoom stayed at ${finalCamera.zoom}", finalCamera.zoom > pinchStartCamera.zoom + 0.05f)
                val expectedPinchCamera = zoomCommands.fold(pinchStartCamera, ::applyZoom)
                assertEquals(expectedPinchCamera.centerX, finalCamera.centerX, 0.08f)
                assertEquals(expectedPinchCamera.centerY, finalCamera.centerY, 0.08f)
                assertEquals(expectedPinchCamera.zoom, finalCamera.zoom, 0.02f)
            } finally {
                screenshot.recycle()
            }
        }
    }

    @Test fun isolatedCanvasSlowSubSlopGesturesAccumulateWithoutInspectionTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val generated = WorldGenerator.generate(1222L)
        val world = WorldKnowledge.observe(
            generated.copy(actor = generated.actor.copy(
                presence = PresenceMode.WORLD,
                structureId = null,
                action = ActionId.WALK,
                facing = Direction.EAST
            )),
            22,
            generated.lastSimulatedAt
        )
        val initial = projectWorldState(world, viewport = WorldUiViewport(30, 22), petStage = "baby")
        val commands = CopyOnWriteArrayList<WorldUiCommand>()
        val latestCamera = AtomicReference(initial.camera)
        val intent = Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ComponentActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    var snapshot by remember { mutableStateOf(initial) }
                    LlamaDroidTheme(dynamicColor = false) {
                        WorldScreen(
                            snapshot,
                            WorldUiCallbacks { command ->
                                commands.add(command)
                                when (command) {
                                    is WorldUiCommand.PanCamera -> {
                                        val next = snapshot.camera.copy(
                                            centerX = snapshot.camera.centerX + command.deltaX,
                                            centerY = snapshot.camera.centerY + command.deltaY,
                                            mode = WorldCameraMode.FREE
                                        )
                                        snapshot = snapshot.copy(camera = next)
                                        latestCamera.set(next)
                                    }
                                    is WorldUiCommand.ZoomCamera -> {
                                        val next = applyZoom(snapshot.camera, command)
                                        snapshot = snapshot.copy(camera = next)
                                        latestCamera.set(next)
                                    }
                                    else -> Unit
                                }
                            },
                            loadAssets = false
                        )
                    }
                }
            }
            Thread.sleep(350L)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val width = screenshot.width.toFloat()
                val height = screenshot.height.toFloat()
                val y = height * 0.35f

                // Each move is one screen pixel, below the normal touch slop. The
                // total drag must still move the camera and never become a tap.
                val dragStarted = SystemClock.uptimeMillis()
                val dragX = width * 0.75f
                injectTouch(MotionEvent.ACTION_DOWN, dragX, y, dragStarted)
                repeat(24) { step ->
                    Thread.sleep(8L)
                    injectTouch(MotionEvent.ACTION_MOVE, dragX - (step + 1), y, dragStarted)
                }
                injectTouch(MotionEvent.ACTION_UP, dragX - 24f, y, dragStarted)
                Thread.sleep(150L)
                val panCommands = commands.filterIsInstance<WorldUiCommand.PanCamera>()
                assertTrue("Slow drag emitted no camera updates", panCommands.isNotEmpty())
                assertTrue("Slow drag did not preserve multiple increments", panCommands.size >= 3)
                assertTrue(
                    "Slow drag covered only ${panCommands.sumOf { abs(it.deltaX).toDouble() }} tiles",
                    panCommands.sumOf { abs(it.deltaX).toDouble() } > 0.1
                )
                assertTrue("Slow drag was misread as inspection", commands.none { it is WorldUiCommand.Inspect })
                assertEquals(
                    initial.camera.centerX + panCommands.sumOf { it.deltaX.toDouble() }.toFloat(),
                    latestCamera.get().centerX,
                    0.08f
                )

                // Every spread increment is below the detector's per-event zoom
                // slop; only cumulative scale can start this pinch.
                val pinchStartCamera = latestCamera.get()
                val pinchStarted = SystemClock.uptimeMillis()
                val pinchY = height * 0.45f
                val firstPinchX = width * 0.32f
                val secondPinchX = width * 0.46f
                injectTouch(MotionEvent.ACTION_DOWN, firstPinchX, pinchY, pinchStarted)
                injectMultiTouch(
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    firstPinchX,
                    pinchY,
                    secondPinchX,
                    pinchY,
                    pinchStarted
                )
                val zoomStartCommandIndex = commands.size
                repeat(80) { step ->
                    Thread.sleep(8L)
                    val spread = width * 0.00015f * (step + 1)
                    injectMultiTouch(
                        MotionEvent.ACTION_MOVE,
                        firstPinchX - spread,
                        pinchY,
                        secondPinchX + spread,
                        pinchY,
                        pinchStarted
                    )
                }
                val finalSpread = width * 0.00015f * 80
                injectMultiTouch(
                    MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    firstPinchX - finalSpread,
                    pinchY,
                    secondPinchX + finalSpread,
                    pinchY,
                    pinchStarted
                )
                injectTouch(MotionEvent.ACTION_UP, firstPinchX - finalSpread, pinchY, pinchStarted)
                Thread.sleep(150L)
                val zoomCommands = commands
                    .drop(zoomStartCommandIndex)
                    .filterIsInstance<WorldUiCommand.ZoomCamera>()
                assertTrue("Slow pinch emitted no zoom updates", zoomCommands.isNotEmpty())
                val cumulativeFactor = zoomCommands.fold(1f) { product, command -> product * command.factor }
                assertTrue("Slow pinch accumulated only $cumulativeFactor", cumulativeFactor > 1.08f)
                assertTrue("Slow pinch was misread as inspection", commands.none { it is WorldUiCommand.Inspect })
                val finalCamera = latestCamera.get()
                val expectedPinchCamera = zoomCommands.fold(pinchStartCamera, ::applyZoom)
                assertEquals(expectedPinchCamera.centerX, finalCamera.centerX, 0.08f)
                assertEquals(expectedPinchCamera.centerY, finalCamera.centerY, 0.08f)
                assertEquals(expectedPinchCamera.zoom, finalCamera.zoom, 0.02f)
            } finally {
                screenshot.recycle()
            }
        }
    }

    @Test fun parentConsumedCanvasTapDoesNotEmitInspection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val generated = WorldGenerator.generate(1223L)
        val world = WorldKnowledge.observe(
            generated.copy(actor = generated.actor.copy(
                presence = PresenceMode.WORLD,
                structureId = null,
                action = ActionId.WALK,
                facing = Direction.EAST
            )),
            22,
            generated.lastSimulatedAt
        )
        val initial = projectWorldState(world, viewport = WorldUiViewport(30, 22), petStage = "baby")
        val commands = CopyOnWriteArrayList<WorldUiCommand>()
        val intent = Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ComponentActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    LlamaDroidTheme(dynamicColor = false) {
                        WorldScreen(
                            initial,
                            WorldUiCallbacks { commands.add(it) },
                            modifier = androidx.compose.ui.Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial
                                    ).consume()
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                            loadAssets = false
                        )
                    }
                }
            }
            Thread.sleep(350L)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val tapTime = SystemClock.uptimeMillis()
                val x = screenshot.width * 0.5f
                val y = screenshot.height * 0.35f
                injectTouch(MotionEvent.ACTION_DOWN, x, y, tapTime)
                injectTouch(MotionEvent.ACTION_UP, x, y, tapTime)
                Thread.sleep(200L)
                assertTrue(
                    "A parent-consumed tap reached the world inspector",
                    commands.none { it is WorldUiCommand.Inspect }
                )
            } finally {
                screenshot.recycle()
            }
        }
    }

    private fun applyZoom(
        base: WorldCameraUi,
        command: WorldUiCommand.ZoomCamera
    ): WorldCameraUi {
        val nextZoom = (base.zoom * command.factor).coerceIn(0.5f, 4f)
        val scale = base.zoom / nextZoom
        return base.copy(
            centerX = command.focusX + (base.centerX - command.focusX) * scale,
            centerY = command.focusY + (base.centerY - command.focusY) * scale,
            zoom = nextZoom,
            mode = WorldCameraMode.FREE,
            followActorId = null
        )
    }

    private fun injectTouch(action: Int, x: Float, y: Float, downTime: Long) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        try {
            // Keep the one-pointer portions of a pinch on the same source as the
            // explicit two-pointer events. A source change at POINTER_DOWN can
            // make InputDispatcher drop the gesture stream before Compose sees it.
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)
            InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
        } finally { event.recycle() }
    }

    private fun injectMultiTouch(
        action: Int,
        firstX: Float,
        firstY: Float,
        secondX: Float,
        secondY: Float,
        downTime: Long
    ) {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = firstX
                y = firstY
                pressure = 1f
                size = 1f
            },
            MotionEvent.PointerCoords().apply {
                x = secondX
                y = secondY
                pressure = 1f
                size = 1f
            }
        )
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            2,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
    }
}
