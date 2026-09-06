package com.example.llamadroid.ui.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.llamadroid.ui.walkthrough.WalkthroughDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

private enum class InpaintMaskTool {
    ADD,
    ERASE
}

private data class LoadedInpaintEditor(
    val source: Bitmap,
    val raster: InpaintMaskRaster,
    val overlay: Bitmap
)

/** Full-screen, touch-first editor which produces the native grayscale inpaint mask. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InpaintMaskEditorDialog(
    sourcePath: String,
    initialMaskPath: String?,
    onDismiss: () -> Unit,
    onSave: (InpaintMaskRaster) -> Unit
) {
    var loaded by remember(sourcePath, initialMaskPath) { mutableStateOf<LoadedInpaintEditor?>(null) }
    var loadFailed by remember(sourcePath, initialMaskPath) { mutableStateOf(false) }
    LaunchedEffect(sourcePath, initialMaskPath) {
        loaded = withContext(Dispatchers.IO) {
            runCatching { loadInpaintEditor(sourcePath, initialMaskPath) }.getOrNull()
        }
        loadFailed = loaded == null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val editor = loaded
            // BitmapFactory allocates native pixel buffers. Dispose them only after this
            // loaded editor leaves composition so the active Canvas can finish its last frame.
            DisposableEffect(editor) {
                onDispose {
                    editor?.source?.let { bitmap ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                    editor?.overlay?.let { bitmap ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }
            when {
                editor != null -> InpaintMaskEditorContent(editor, onDismiss, onSave)
                loadFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.imagegen_inpaint_editor_load_error), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                    }
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InpaintMaskEditorContent(
    editor: LoadedInpaintEditor,
    onDismiss: () -> Unit,
    onSave: (InpaintMaskRaster) -> Unit
) {
    val raster = editor.raster
    val overlayBitmap = editor.overlay
    val sourceImage = remember(editor.source) { editor.source.asImageBitmap() }
    val overlayImage = remember(overlayBitmap) { overlayBitmap.asImageBitmap() }
    var overlayVersion by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(InpaintMaskTool.ADD) }
    var brushRadius by remember { mutableFloatStateOf(32f) }
    var softness by remember { mutableFloatStateOf(0.15f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val undo = remember { mutableStateListOf<ByteArray>() }
    val redo = remember { mutableStateListOf<ByteArray>() }

    // Raster pixels are deliberately kept outside Compose for cheap brush updates. Read the
    // revision here so controls such as Save observe every raster mutation as Compose state.
    @Suppress("UNUSED_VARIABLE")
    val observedOverlayVersion = overlayVersion
    val canSaveMask = !raster.isEmpty()

    fun refreshOverlay() {
        overlayBitmap.setPixels(
            raster.toOverlayArgb(),
            0,
            raster.width,
            0,
            0,
            raster.width,
            raster.height
        )
        overlayVersion++
    }

    fun rememberUndo() {
        undo += raster.snapshot()
        while (undo.size > MAX_MASK_UNDO) undo.removeAt(0)
        redo.clear()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.imagegen_inpaint_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = {
                    com.example.llamadroid.ui.walkthrough.FeatureGuideAction()
                    TextButton(
                        enabled = canSaveMask,
                        onClick = {
                            onSave(InpaintMaskRaster.fromBytes(raster.width, raster.height, raster.snapshot()))
                        }
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Text(stringResource(R.string.action_save))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.imagegen_inpaint_editor_tutorial),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black, RoundedCornerShape(12.dp))
                    .pointerInput(raster, tool, brushRadius, softness) {
                        awaitEachGesture {
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            fun imagePosition(displayPosition: Offset): Offset {
                                val baseScale = min(
                                    size.width.toFloat() / raster.width.toFloat(),
                                    size.height.toFloat() / raster.height.toFloat()
                                )
                                val displayScale = baseScale * zoom
                                val displayWidth = raster.width * displayScale
                                val displayHeight = raster.height * displayScale
                                val left = (size.width - displayWidth) / 2f + pan.x
                                val top = (size.height - displayHeight) / 2f + pan.y
                                return Offset(
                                    x = (displayPosition.x - left) / displayScale,
                                    y = (displayPosition.y - top) / displayScale
                                )
                            }

                            var previousPaintPosition: Offset? = imagePosition(firstDown.position)
                            var recordedUndo = false
                            var usedMultiTouch = false

                            fun paint(displayPosition: Offset) {
                                val currentPosition = imagePosition(displayPosition)
                                if (currentPosition.x !in 0f..raster.width.toFloat() ||
                                    currentPosition.y !in 0f..raster.height.toFloat()
                                ) return
                                if (!recordedUndo) {
                                    rememberUndo()
                                    recordedUndo = true
                                }
                                val previous = previousPaintPosition ?: currentPosition
                                raster.paintLine(
                                    fromX = previous.x,
                                    fromY = previous.y,
                                    toX = currentPosition.x,
                                    toY = currentPosition.y,
                                    radius = brushRadius,
                                    softness = softness,
                                    erase = tool == InpaintMaskTool.ERASE
                                )
                                previousPaintPosition = currentPosition
                                refreshOverlay()
                            }

                            var keepGoing: Boolean
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    usedMultiTouch = true
                                    val nextZoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_MASK_ZOOM)
                                    pan += event.calculatePan()
                                    zoom = nextZoom
                                    previousPaintPosition = null
                                    pressed.forEach { it.consume() }
                                } else {
                                    val change = pressed.firstOrNull()
                                    if (change != null) {
                                        paint(change.position)
                                        change.consume()
                                    } else if (!recordedUndo && !usedMultiTouch) {
                                        // A tap has no intermediate pressed event; still apply one brush dab.
                                        paint(firstDown.position)
                                    }
                                }
                                keepGoing = event.changes.any { it.pressed }
                            } while (keepGoing)
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // Reading this state invalidates the draw scope after each mask mutation.
                    @Suppress("UNUSED_VARIABLE") val version = overlayVersion
                    val baseScale = min(
                        size.width / raster.width.toFloat(),
                        size.height / raster.height.toFloat()
                    )
                    val displayScale = baseScale * zoom
                    val displayWidth = (raster.width * displayScale).roundToInt().coerceAtLeast(1)
                    val displayHeight = (raster.height * displayScale).roundToInt().coerceAtLeast(1)
                    val left = ((size.width - displayWidth) / 2f + pan.x).roundToInt()
                    val top = ((size.height - displayHeight) / 2f + pan.y).roundToInt()
                    val destination = IntOffset(left, top)
                    val destinationSize = IntSize(displayWidth, displayHeight)
                    drawImage(sourceImage, dstOffset = destination, dstSize = destinationSize)
                    drawImage(overlayImage, dstOffset = destination, dstSize = destinationSize)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(InpaintMaskTool.entries) { item ->
                            FilterChip(
                                selected = tool == item,
                                onClick = { tool = item },
                                label = {
                                    Text(
                                        stringResource(
                                            if (item == InpaintMaskTool.ADD) R.string.imagegen_inpaint_editor_add
                                            else R.string.imagegen_inpaint_editor_erase
                                        )
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                        }
                        item {
                            IconButton(
                                enabled = undo.isNotEmpty(),
                                onClick = {
                                    val snapshot = undo.removeAt(undo.lastIndex)
                                    redo += raster.snapshot()
                                    raster.restore(snapshot)
                                    refreshOverlay()
                                }
                            ) { Icon(Icons.Default.Undo, stringResource(R.string.action_undo)) }
                        }
                        item {
                            IconButton(
                                enabled = redo.isNotEmpty(),
                                onClick = {
                                    val snapshot = redo.removeAt(redo.lastIndex)
                                    undo += raster.snapshot()
                                    raster.restore(snapshot)
                                    refreshOverlay()
                                }
                            ) { Icon(Icons.Default.Redo, stringResource(R.string.action_redo)) }
                        }
                        item {
                            IconButton(onClick = {
                                rememberUndo()
                                raster.invert()
                                refreshOverlay()
                            }) { Icon(Icons.Default.InvertColors, stringResource(R.string.imagegen_inpaint_editor_invert)) }
                        }
                        item {
                            IconButton(onClick = {
                                rememberUndo()
                                raster.clear()
                                refreshOverlay()
                            }) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.action_clear)) }
                        }
                        item {
                            IconButton(onClick = {
                                zoom = 1f
                                pan = Offset.Zero
                            }) { Icon(Icons.Default.Clear, stringResource(R.string.imagegen_inpaint_editor_reset_view)) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.imagegen_inpaint_editor_brush_size),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = brushRadius,
                            onValueChange = { brushRadius = it },
                            valueRange = 4f..128f,
                            modifier = Modifier.weight(2f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.imagegen_inpaint_editor_softness),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = softness,
                            onValueChange = { softness = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(2f)
                        )
                    }
                }
            }
        }
    }
}

private fun loadInpaintEditor(sourcePath: String, initialMaskPath: String?): LoadedInpaintEditor {
    val source = BitmapFactory.decodeFile(sourcePath) ?: error("Unreadable source image")
    return try {
        val raster = initialMaskPath
            ?.takeIf { File(it).isFile }
            ?.let { path -> decodeMaskRaster(path, source.width, source.height) }
            ?: InpaintMaskRaster.empty(source.width, source.height)
        val overlay = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(raster.toOverlayArgb(), 0, source.width, 0, 0, source.width, source.height)
        }
        LoadedInpaintEditor(source, raster, overlay)
    } catch (error: Throwable) {
        if (!source.isRecycled) source.recycle()
        throw error
    }
}

private fun decodeMaskRaster(path: String, targetWidth: Int, targetHeight: Int): InpaintMaskRaster {
    val bitmap = BitmapFactory.decodeFile(path) ?: error("Unreadable mask image")
    return try {
        require(
            InpaintMaskRaster.compatibleAspectRatio(bitmap.width, bitmap.height, targetWidth, targetHeight)
        ) { "Mask aspect ratio does not match the source" }
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luma = ByteArray(argb.size) { index ->
            val color = argb[index]
            val alpha = color ushr 24 and 0xff
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            val luminance = (red * 299 + green * 587 + blue * 114) / 1000
            min(alpha, luminance).toByte()
        }
        if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
            InpaintMaskRaster.fromBytes(targetWidth, targetHeight, luma)
        } else {
            InpaintMaskRaster.resizeNearest(bitmap.width, bitmap.height, luma, targetWidth, targetHeight)
        }
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

private const val MAX_MASK_UNDO = 20
private const val MAX_MASK_ZOOM = 6f
