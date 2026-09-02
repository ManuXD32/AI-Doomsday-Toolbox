package com.example.llamadroid.ui.ai

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.service.MangaPaintedOcrSupport
import com.example.llamadroid.service.MangaPaintedOcrWorkspace
import com.example.llamadroid.service.MangaPaintedOcrWorkspaceManager
import com.example.llamadroid.service.MangaPaintedOcrWorkspaceRef
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class PaintedMaskTool { PAINT, ERASE, PAN, CIRCLE, SQUARE, RECTANGLE }

private enum class PaintedShapeKind { CIRCLE, SQUARE, RECTANGLE }

private data class PendingPaintedShape(
    val kind: PaintedShapeKind,
    val bounds: Rect
) {
    val normalizedBounds: Rect
        get() = Rect(
            left = min(bounds.left, bounds.right),
            top = min(bounds.top, bounds.bottom),
            right = max(bounds.left, bounds.right),
            bottom = max(bounds.top, bounds.bottom)
        )
}

private enum class ShapeGesture { CREATE, MOVE, RESIZE }

private data class LoadedPaintedEditor(
    val source: Bitmap,
    val raster: InpaintMaskRaster,
    val overlay: Bitmap
)

/**
 * Reusable bounded mask editor for a single manga/PDF page. It deliberately
 * stores only a preview-sized raster; the native OCR path reopens the source
 * page at runtime and applies the normalized regions to that page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaPaintedMaskEditorDialog(
    context: android.content.Context,
    workspace: MangaPaintedOcrWorkspace,
    pageIndex: Int,
    onDismiss: () -> Unit,
    onWorkspaceChanged: (MangaPaintedOcrWorkspace) -> Unit
) {
    var loaded by remember(workspace.ref, pageIndex) { mutableStateOf<LoadedPaintedEditor?>(null) }
    var loadFailed by remember(workspace.ref, pageIndex) { mutableStateOf(false) }
    LaunchedEffect(workspace.ref, pageIndex) {
        val result = runCatching {
            val preview = MangaPaintedOcrWorkspaceManager.decodePagePreview(context, workspace, pageIndex)
            val review = workspace.reviewFor(pageIndex)
            val maskBytes = MangaPaintedOcrWorkspaceManager.readMask(context, workspace.ref, pageIndex)
            val raster = if (
                maskBytes != null && review != null &&
                review.width > 0 && review.height > 0 &&
                review.width * review.height == maskBytes.size
            ) {
                if (review.width == preview.width && review.height == preview.height) {
                    InpaintMaskRaster.fromBytes(review.width, review.height, maskBytes)
                } else {
                    InpaintMaskRaster.resizeNearest(
                        sourceWidth = review.width,
                        sourceHeight = review.height,
                        source = maskBytes,
                        targetWidth = preview.width,
                        targetHeight = preview.height
                    )
                }
            } else {
                InpaintMaskRaster.empty(preview.width, preview.height)
            }
            val overlay = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(raster.toOverlayArgb(), 0, raster.width, 0, 0, raster.width, raster.height)
            }
            LoadedPaintedEditor(preview, raster, overlay)
        }.getOrNull()
        if (result == null) {
            loadFailed = true
        } else {
            loaded = result
            loadFailed = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // BitmapFactory allocates native pixel buffers. Dispose an editor only after the
            // corresponding composition leaves the tree so the active Canvas can finish its
            // last frame. This also makes page changes safe: no bitmap is recycled while it is
            // still referenced by a Compose draw pass.
            DisposableEffect(loaded) {
                val editor = loaded
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
                loaded != null -> PaintedMaskEditorContent(
                    loaded = loaded!!,
                    context = context,
                    workspace = workspace,
                    pageIndex = pageIndex,
                    onDismiss = onDismiss,
                    onWorkspaceChanged = onWorkspaceChanged
                )
                loadFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.workflow_manga_painted_editor_error), color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                    }
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.workflow_manga_painted_editor_loading))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaintedMaskEditorContent(
    loaded: LoadedPaintedEditor,
    context: android.content.Context,
    workspace: MangaPaintedOcrWorkspace,
    pageIndex: Int,
    onDismiss: () -> Unit,
    onWorkspaceChanged: (MangaPaintedOcrWorkspace) -> Unit
) {
    val raster = loaded.raster
    val overlay = loaded.overlay
    val sourceImage = remember(loaded.source) { loaded.source.asImageBitmap() }
    val overlayImage = remember(overlay) { overlay.asImageBitmap() }
    var tool by remember { mutableStateOf(PaintedMaskTool.PAINT) }
    var overlayVersion by remember { mutableIntStateOf(0) }
    var brushRadius by remember { mutableFloatStateOf((raster.width * 0.012f).coerceIn(8f, 42f)) }
    var eraserRadius by remember { mutableFloatStateOf((raster.width * 0.018f).coerceIn(10f, 64f)) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var pendingShape by remember { mutableStateOf<PendingPaintedShape?>(null) }
    val undo = remember { mutableStateListOf<ByteArray>() }
    val redo = remember { mutableStateListOf<ByteArray>() }
    var saving by remember { mutableStateOf(false) }
    val mounted = remember { AtomicBoolean(true) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { mounted.set(false) } }

    // Raster pixels live outside the snapshot system. Reading the revision in
    // composition makes Save/Clear react immediately after a brush mutation.
    @Suppress("UNUSED_VARIABLE")
    val observedOverlayVersion = overlayVersion
    val canSaveMask = !raster.isEmpty() || pendingShape != null

    fun refresh() {
        overlay.setPixels(raster.toOverlayArgb(), 0, raster.width, 0, 0, raster.width, raster.height)
        overlayVersion++
    }

    fun rememberUndo() {
        undo += raster.snapshot()
        while (undo.size > MAX_PAINTED_MASK_UNDO) undo.removeAt(0)
        redo.clear()
    }

    fun applyPendingShape(recordUndo: Boolean = true) {
        val shape = pendingShape ?: return
        val bounds = shape.normalizedBounds
        if (bounds.width < MIN_PAINTED_SHAPE_SIZE || bounds.height < MIN_PAINTED_SHAPE_SIZE) {
            pendingShape = null
            return
        }
        if (recordUndo) rememberUndo()
        when (shape.kind) {
            PaintedShapeKind.CIRCLE -> raster.paintEllipse(
                bounds.left, bounds.top, bounds.right, bounds.bottom, erase = false
            )
            PaintedShapeKind.SQUARE,
            PaintedShapeKind.RECTANGLE -> raster.paintRectangle(
                bounds.left, bounds.top, bounds.right, bounds.bottom, erase = false
            )
        }
        pendingShape = null
        refresh()
    }

    fun saveMask() {
        if (saving || !canSaveMask) return
        applyPendingShape()
        if (raster.isEmpty()) return
        saving = true
        scope.launch {
            val updated = runCatching {
                MangaPaintedOcrWorkspaceManager.savePageMask(
                    context = context,
                    ref = workspace.ref,
                    pageIndex = pageIndex,
                    width = raster.width,
                    height = raster.height,
                    mask = raster.snapshot()
                )
            }.getOrNull()
            saving = false
            if (updated != null && mounted.get()) {
                onWorkspaceChanged(updated)
                onDismiss()
            }
        }
    }

    fun markNoText() {
        if (saving) return
        saving = true
        scope.launch {
            val updated = runCatching {
                MangaPaintedOcrWorkspaceManager.markNoText(context, workspace.ref, pageIndex)
            }.getOrNull()
            saving = false
            if (updated != null && mounted.get()) {
                onWorkspaceChanged(updated)
                onDismiss()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workflow_manga_painted_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !saving) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = {
                    TextButton(onClick = ::saveMask, enabled = !saving && canSaveMask) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Text(stringResource(R.string.workflow_manga_painted_editor_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.workflow_manga_painted_editor_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black, RoundedCornerShape(12.dp))
                    .pointerInput(tool, raster, brushRadius, eraserRadius) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        fun imagePosition(position: Offset): Offset {
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
                                (position.x - left) / displayScale,
                                (position.y - top) / displayScale
                            )
                        }
                        fun clampToRaster(point: Offset) = Offset(
                            point.x.coerceIn(0f, raster.width.toFloat()),
                            point.y.coerceIn(0f, raster.height.toFloat())
                        )
                        fun shapeForDrag(kind: PaintedShapeKind, start: Offset, end: Offset): PendingPaintedShape {
                            var adjustedEnd = end
                            if (kind != PaintedShapeKind.RECTANGLE) {
                                val extent = max(abs(end.x - start.x), abs(end.y - start.y))
                                val directionX = if (end.x < start.x) -1f else 1f
                                val directionY = if (end.y < start.y) -1f else 1f
                                adjustedEnd = Offset(start.x + extent * directionX, start.y + extent * directionY)
                            }
                            return PendingPaintedShape(kind, Rect(start, clampToRaster(adjustedEnd)))
                        }
                        fun clampShape(shape: PendingPaintedShape): PendingPaintedShape {
                            val bounds = shape.normalizedBounds
                            val shiftX = when {
                                bounds.left < 0f -> -bounds.left
                                bounds.right > raster.width -> raster.width - bounds.right
                                else -> 0f
                            }
                            val shiftY = when {
                                bounds.top < 0f -> -bounds.top
                                bounds.bottom > raster.height -> raster.height - bounds.bottom
                                else -> 0f
                            }
                            return shape.copy(bounds = shape.bounds.translate(Offset(shiftX, shiftY)))
                        }

                        val start = clampToRaster(imagePosition(firstDown.position))
                        var previousImage = start
                        var previousDisplay = firstDown.position
                        var recordedUndo = false
                        var painted = false
                        var usedMultiTouch = false
                        val shapeKind = tool.toPaintedShapeKind()
                        var workingShape = pendingShape
                        val handleRadius = 30f / min(
                            size.width.toFloat() / raster.width.toFloat(),
                            size.height.toFloat() / raster.height.toFloat()
                        ) / zoom
                        val startingBounds = workingShape?.normalizedBounds
                        val shapeGesture = if (shapeKind != null && startingBounds != null) {
                            when {
                                (start - startingBounds.bottomRight).getDistance() <= handleRadius -> ShapeGesture.RESIZE
                                startingBounds.contains(start) -> ShapeGesture.MOVE
                                else -> ShapeGesture.CREATE
                            }
                        } else {
                            ShapeGesture.CREATE
                        }
                        if (shapeKind != null && shapeGesture == ShapeGesture.CREATE) {
                            workingShape = PendingPaintedShape(shapeKind, Rect(start, start))
                            pendingShape = workingShape
                        }

                        var keepGoing: Boolean
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                usedMultiTouch = true
                                zoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_PAINTED_MASK_ZOOM)
                                pan += event.calculatePan()
                                pressed.forEach { it.consume() }
                            } else {
                                val change = pressed.firstOrNull()
                                if (change != null) {
                                    val current = clampToRaster(imagePosition(change.position))
                                    when {
                                        tool == PaintedMaskTool.PAN -> {
                                            pan += change.position - previousDisplay
                                        }
                                        tool == PaintedMaskTool.PAINT || tool == PaintedMaskTool.ERASE -> {
                                            if (!recordedUndo) {
                                                rememberUndo()
                                                recordedUndo = true
                                            }
                                            raster.paintLine(
                                                previousImage.x,
                                                previousImage.y,
                                                current.x,
                                                current.y,
                                                radius = if (tool == PaintedMaskTool.ERASE) eraserRadius else brushRadius,
                                                softness = 0f,
                                                erase = tool == PaintedMaskTool.ERASE
                                            )
                                            painted = true
                                            refresh()
                                        }
                                        shapeKind != null -> {
                                            val original = workingShape
                                            workingShape = when (shapeGesture) {
                                                ShapeGesture.CREATE -> shapeForDrag(shapeKind, start, current)
                                                ShapeGesture.MOVE -> original?.copy(
                                                    bounds = original.bounds.translate(current - previousImage)
                                                )?.let(::clampShape)
                                                ShapeGesture.RESIZE -> original?.let {
                                                    shapeForDrag(it.kind, it.normalizedBounds.topLeft, current)
                                                }
                                            }
                                            pendingShape = workingShape
                                        }
                                    }
                                    previousImage = current
                                    previousDisplay = change.position
                                    change.consume()
                                }
                            }
                            keepGoing = event.changes.any { it.pressed }
                        } while (keepGoing)

                        if (
                            !usedMultiTouch && !painted &&
                            (tool == PaintedMaskTool.PAINT || tool == PaintedMaskTool.ERASE)
                        ) {
                            rememberUndo()
                            raster.paintCircle(
                                start.x,
                                start.y,
                                if (tool == PaintedMaskTool.ERASE) eraserRadius else brushRadius,
                                softness = 0f,
                                erase = tool == PaintedMaskTool.ERASE
                            )
                            refresh()
                        }
                    }
                }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_VARIABLE") val version = overlayVersion
                    val baseScale = min(size.width / raster.width.toFloat(), size.height / raster.height.toFloat())
                    val scale = baseScale * zoom
                    val width = (raster.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (raster.height * scale).roundToInt().coerceAtLeast(1)
                    val left = (size.width - width) / 2f + pan.x
                    val top = (size.height - height) / 2f + pan.y
                    val offset = IntOffset(left.roundToInt(), top.roundToInt())
                    val destination = IntSize(width, height)
                    drawImage(sourceImage, dstOffset = offset, dstSize = destination)
                    drawImage(overlayImage, dstOffset = offset, dstSize = destination)
                    pendingShape?.let { shape ->
                        val bounds = shape.normalizedBounds
                        val displayRect = Rect(
                            left + bounds.left * scale,
                            top + bounds.top * scale,
                            left + bounds.right * scale,
                            top + bounds.bottom * scale
                        )
                        val fill = Color(0x66FF3B30)
                        val outline = Color(0xFFFFC107)
                        if (shape.kind == PaintedShapeKind.CIRCLE) {
                            drawOval(fill, topLeft = displayRect.topLeft, size = displayRect.size)
                            drawOval(outline, topLeft = displayRect.topLeft, size = displayRect.size, style = Stroke(3.dp.toPx()))
                        } else {
                            drawRect(fill, topLeft = displayRect.topLeft, size = displayRect.size)
                            drawRect(outline, topLeft = displayRect.topLeft, size = displayRect.size, style = Stroke(3.dp.toPx()))
                        }
                        drawCircle(outline, radius = 8.dp.toPx(), center = displayRect.topLeft)
                        drawCircle(outline, radius = 8.dp.toPx(), center = displayRect.bottomRight)
                    }
                }
            }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    Modifier
                        .heightIn(max = 310.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PaintedMaskTool.entries) { item ->
                            FilterChip(
                                selected = tool == item,
                                onClick = {
                                    val nextKind = item.toPaintedShapeKind()
                                    if (nextKind == null || pendingShape?.kind != nextKind) {
                                        pendingShape = null
                                    }
                                    tool = item
                                },
                                label = { Text(stringResource(item.labelResource())) },
                                leadingIcon = { Icon(item.icon(), null, Modifier.size(18.dp)) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            enabled = undo.isNotEmpty(),
                            onClick = {
                                val snapshot = undo.removeAt(undo.lastIndex)
                                redo += raster.snapshot()
                                raster.restore(snapshot)
                                pendingShape = null
                                refresh()
                            }
                        ) { Icon(Icons.Default.Undo, stringResource(R.string.action_undo)) }
                        IconButton(
                            enabled = redo.isNotEmpty(),
                            onClick = {
                                val snapshot = redo.removeAt(redo.lastIndex)
                                undo += raster.snapshot()
                                raster.restore(snapshot)
                                pendingShape = null
                                refresh()
                            }
                        ) { Icon(Icons.Default.Redo, stringResource(R.string.action_redo)) }
                        IconButton(
                            enabled = zoom > 1f,
                            onClick = {
                                zoom = (zoom - 0.5f).coerceAtLeast(1f)
                                if (zoom == 1f) pan = Offset.Zero
                            }
                        ) { Icon(Icons.Default.ZoomOut, stringResource(R.string.workflow_manga_painted_editor_zoom_out)) }
                        Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                        IconButton(
                            enabled = zoom < MAX_PAINTED_MASK_ZOOM,
                            onClick = { zoom = (zoom + 0.5f).coerceAtMost(MAX_PAINTED_MASK_ZOOM) }
                        ) { Icon(Icons.Default.ZoomIn, stringResource(R.string.workflow_manga_painted_editor_zoom_in)) }
                        IconButton(
                            onClick = { zoom = 1f; pan = Offset.Zero }
                        ) { Icon(Icons.Default.Clear, stringResource(R.string.workflow_manga_painted_editor_reset_view)) }
                        IconButton(
                            enabled = !raster.isEmpty(),
                            onClick = {
                                rememberUndo()
                                raster.clear()
                                pendingShape = null
                                refresh()
                            }
                        ) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.workflow_manga_painted_editor_clear)) }
                    }
                    pendingShape?.let {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { applyPendingShape() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Check, null)
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(R.string.workflow_manga_painted_editor_apply_shape))
                            }
                            OutlinedButton(onClick = { pendingShape = null }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }
                    MaskSizeSlider(
                        label = stringResource(R.string.workflow_manga_painted_editor_brush_size),
                        value = brushRadius,
                        onValueChange = { brushRadius = it },
                        maxRadius = (raster.width * 0.08f).coerceIn(48f, 160f)
                    )
                    MaskSizeSlider(
                        label = stringResource(R.string.workflow_manga_painted_editor_eraser_size),
                        value = eraserRadius,
                        onValueChange = { eraserRadius = it },
                        maxRadius = (raster.width * 0.12f).coerceIn(64f, 220f)
                    )
                    FilledTonalButton(
                        onClick = ::markNoText,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.TextFields, null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.workflow_manga_painted_editor_no_text), maxLines = 2)
                    }
                    Button(
                        onClick = ::saveMask,
                        enabled = !saving && canSaveMask,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Save, null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.workflow_manga_painted_editor_save), maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun MaskSizeSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    maxRadius: Float
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.coerceIn(3f, maxRadius),
            onValueChange = onValueChange,
            valueRange = 3f..maxRadius,
            modifier = Modifier.weight(2f)
        )
    }
}

private fun PaintedMaskTool.toPaintedShapeKind(): PaintedShapeKind? = when (this) {
    PaintedMaskTool.CIRCLE -> PaintedShapeKind.CIRCLE
    PaintedMaskTool.SQUARE -> PaintedShapeKind.SQUARE
    PaintedMaskTool.RECTANGLE -> PaintedShapeKind.RECTANGLE
    else -> null
}

private fun PaintedMaskTool.labelResource(): Int = when (this) {
    PaintedMaskTool.PAINT -> R.string.workflow_manga_painted_editor_paint
    PaintedMaskTool.ERASE -> R.string.workflow_manga_painted_editor_erase
    PaintedMaskTool.PAN -> R.string.workflow_manga_painted_editor_pan
    PaintedMaskTool.CIRCLE -> R.string.workflow_manga_painted_editor_circle
    PaintedMaskTool.SQUARE -> R.string.workflow_manga_painted_editor_square
    PaintedMaskTool.RECTANGLE -> R.string.workflow_manga_painted_editor_rectangle
}

private fun PaintedMaskTool.icon() = when (this) {
    PaintedMaskTool.PAINT -> Icons.Default.Brush
    PaintedMaskTool.ERASE -> Icons.Default.Clear
    PaintedMaskTool.PAN -> Icons.Default.PanTool
    PaintedMaskTool.CIRCLE -> Icons.Default.RadioButtonUnchecked
    PaintedMaskTool.SQUARE,
    PaintedMaskTool.RECTANGLE -> Icons.Default.CropSquare
}

private const val MAX_PAINTED_MASK_UNDO = 12
private const val MAX_PAINTED_MASK_ZOOM = 6f
private const val MIN_PAINTED_SHAPE_SIZE = 3f
