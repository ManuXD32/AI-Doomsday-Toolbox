package com.example.llamadroid.ui.easy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.easy.EasyImageEngine
import com.example.llamadroid.data.easy.EasyModeSettings
import com.example.llamadroid.data.easy.EasyModelCatalog
import com.example.llamadroid.data.easy.coerceEasyImageSize
import com.example.llamadroid.data.easy.easySizeLabel
import com.example.llamadroid.service.OnnxImageGenerationService
import com.example.llamadroid.service.OnnxImageGenerationState
import com.example.llamadroid.service.OnnxImageGenerationStateStore
import com.example.llamadroid.service.SDGenerationState
import com.example.llamadroid.service.SDMode
import com.example.llamadroid.service.SDModeStateHolder
import com.example.llamadroid.service.StableDiffusionService
import com.example.llamadroid.ui.ai.BatteryOptimizationWarningDialog
import com.example.llamadroid.ui.ai.rememberAiJobStartupGuard
import com.example.llamadroid.ui.ai.rememberBatteryOptimizationGateState
import com.example.llamadroid.ui.components.AppSectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun decodeSampled(path: String, maxSide: Int = 1024): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private val EASY_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

private fun newestEasyImage(root: File): String? =
    root.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in EASY_IMAGE_EXTENSIONS }
        .maxByOrNull { it.lastModified() }
        ?.absolutePath

/**
 * Easy mode Images tab: prompt, model chip, preset sizes and one Create button. Generation uses
 * the same services and state holders as the advanced image screens, with fixed preset settings;
 * outputs land in `filesDir/easy_output/` and only Easy-origin jobs are shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EasyImagesTab(onOpenModels: () -> Unit, onOpenGallery: () -> Unit) {
    val emptyPromptText = stringResource(R.string.easy_images_empty_prompt)
    val preparingText = stringResource(R.string.easy_images_preparing)
    val launchFailedFormat = stringResource(R.string.easy_images_launch_failed)
    val context = LocalContext.current
    val settings = remember { EasyModeSettings(context) }
    val state = rememberEasyModelState()
    val totalRam = remember { deviceTotalRamBytes(context) }
    val startupGuard = rememberAiJobStartupGuard()
    val batteryGate = rememberBatteryOptimizationGateState()

    // The saved choice is edited on the Models screen; the flow applies a change immediately.
    val savedId by settings.imageModelIdFlow.collectAsState()

    val selection = effectiveEasyImageSelection(savedId, state.installedModels, totalRam)
    val preset = selection?.preset
    var prompt by rememberSaveable { mutableStateOf("") }
    var sizeIndex by rememberSaveable { mutableIntStateOf(0) }
    val size = preset?.let { coerceEasyImageSize(it, it.sizes.getOrNull(sizeIndex)) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf(false) }
    var lastEngine by rememberSaveable { mutableStateOf<String?>(null) }

    // The txt2img holders are shared with the advanced screens: only runs started by Easy count here.
    val sdHolder = SDModeStateHolder.txt2img
    val onnxHolder = OnnxImageGenerationStateStore.txt2img
    val rawSdState by sdHolder.state.collectAsState()
    val rawOnnxState by onnxHolder.state.collectAsState()
    val sdEasy by sdHolder.easyOrigin.collectAsState()
    val onnxEasy by onnxHolder.easyOrigin.collectAsState()
    val sdState = if (sdEasy) rawSdState else SDGenerationState.Idle
    val onnxState = if (onnxEasy) rawOnnxState else OnnxImageGenerationState.Idle
    val foreignBusy = (!sdEasy && rawSdState is SDGenerationState.Generating) ||
        (!onnxEasy && (rawOnnxState is OnnxImageGenerationState.Preparing ||
            rawOnnxState is OnnxImageGenerationState.Generating))
    val otherBusyText = stringResource(R.string.easy_images_other_busy)
    val sdBusy = sdState is SDGenerationState.Generating
    val onnxBusy = onnxState is OnnxImageGenerationState.Preparing || onnxState is OnnxImageGenerationState.Generating
    val busy = pendingStart || sdBusy || onnxBusy
    val shownEngine = when {
        sdBusy -> EasyImageEngine.SDCPP.name
        onnxBusy -> EasyImageEngine.ONNX.name
        lastEngine != null -> lastEngine
        sdState !is SDGenerationState.Idle -> EasyImageEngine.SDCPP.name
        onnxState !is OnnxImageGenerationState.Idle -> EasyImageEngine.ONNX.name
        else -> null
    }

    // Newest Easy image on disk, refreshed whenever a run completes (survives tab switches and restarts).
    val completedPath = (sdState as? SDGenerationState.Complete)?.outputPath
        ?: (onnxState as? OnnxImageGenerationState.Complete)?.outputPath
    val latestPath by produceState<String?>(null, completedPath) {
        value = withContext(Dispatchers.IO) { newestEasyImage(easyOutputRoot(context)) }
    }

    LaunchedEffect(sdState, onnxState, pendingStart) {
        if (!pendingStart) return@LaunchedEffect
        val finished = when (lastEngine) {
            EasyImageEngine.SDCPP.name ->
                sdState is SDGenerationState.Complete || sdState is SDGenerationState.Error
            EasyImageEngine.ONNX.name ->
                onnxState is OnnxImageGenerationState.Complete || onnxState is OnnxImageGenerationState.Error
            else -> true
        }
        if (sdBusy || onnxBusy || finished) pendingStart = false
    }

    fun launch() {
        val current = selection ?: return
        val chosenSize = size ?: return
        val text = prompt.trim()
        if (foreignBusy) {
            errorText = otherBusyText
            return
        }
        if (text.isEmpty()) {
            errorText = emptyPromptText
            return
        }
        errorText = null
        batteryGate.runAfterCheck {
            runCatching {
                pendingStart = true
                lastEngine = current.engine.name
                when (current.engine) {
                    EasyImageEngine.SDCPP -> {
                        sdHolder.easyOrigin.value = true
                        sdHolder.reset()
                        val config = buildEasySdConfig(context, current, text, chosenSize)
                        startupGuard.run("easy_sd_txt2img_start") {
                            ContextCompat.startForegroundService(
                                context,
                                StableDiffusionService.createStartIntent(context, config)
                            )
                        }
                    }
                    EasyImageEngine.ONNX -> {
                        val config = buildEasyOnnxConfig(context, current, text, chosenSize)
                        onnxHolder.easyOrigin.value = true
                        onnxHolder.updateState(
                            OnnxImageGenerationState.Preparing(preparingText)
                        )
                        startupGuard.run("easy_onnx_image_start") {
                            context.startForegroundService(
                                OnnxImageGenerationService.createStartIntent(context, config)
                            )
                        }
                    }
                }
            }.onFailure { error ->
                pendingStart = false
                errorText = launchFailedFormat.replace("%1\$s", error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        when (shownEngine) {
            EasyImageEngine.SDCPP.name ->
                context.startService(StableDiffusionService.createCancelModeIntent(context, SDMode.TXT2IMG))
            EasyImageEngine.ONNX.name ->
                context.startService(OnnxImageGenerationService.createCancelIntent(context))
        }
        pendingStart = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (selection == null) {
            AppSectionCard {
                Text(
                    stringResource(R.string.easy_images_no_model),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.easy_images_get_model), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            val name = selection.entry?.let { easyEntryName(it) } ?: selection.main.filename
            AssistChip(
                onClick = onOpenModels,
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
            if (selection.entry?.let { EasyModelCatalog.exceedsRecommendation(it, totalRam) } == true) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Default.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)
                    )
                    Text(
                        stringResource(R.string.easy_images_warning_ram),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            enabled = !busy,
            placeholder = { Text(stringResource(R.string.easy_images_prompt_hint)) }
        )

        if (preset != null) {
            Text(
                stringResource(R.string.easy_images_size_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                preset.sizes.forEachIndexed { index, option ->
                    FilterChip(
                        selected = option == size,
                        onClick = { sizeIndex = index },
                        enabled = !busy,
                        label = { Text(easySizeLabel(option), maxLines = 1) }
                    )
                }
            }
        }

        errorText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (busy) {
            OutlinedButton(onClick = { cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.easy_images_cancel), maxLines = 1)
            }
        } else {
            Button(
                onClick = { launch() },
                enabled = selection != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.easy_images_create), maxLines = 1)
            }
        }

        EasyImageRunCard(shownEngine, sdState, onnxState, latestPath, onOpenGallery)

        TextButton(onClick = onOpenGallery, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Collections, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.easy_images_open_gallery),
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    BatteryOptimizationWarningDialog(state = batteryGate)
}

@Composable
private fun EasyImageRunCard(
    engine: String?,
    sdState: SDGenerationState,
    onnxState: OnnxImageGenerationState,
    latestPath: String?,
    onOpenGallery: () -> Unit
) {
    var showViewer by remember { mutableStateOf(false) }
    var progress: Float? = null
    var statusText: String? = null
    var outputPath: String? = null
    var error: String? = null
    when (engine) {
        EasyImageEngine.SDCPP.name -> when (val s = sdState) {
            is SDGenerationState.Generating -> {
                progress = s.snapshot.progress
                statusText = stringResource(R.string.easy_images_step, s.snapshot.currentStep, s.snapshot.totalSteps)
            }
            is SDGenerationState.Complete -> outputPath = s.outputPath
            is SDGenerationState.Error -> error = s.message
            SDGenerationState.Idle -> Unit
        }
        EasyImageEngine.ONNX.name -> when (val s = onnxState) {
            is OnnxImageGenerationState.Preparing -> {
                progress = 0f
                statusText = s.status
            }
            is OnnxImageGenerationState.Generating -> {
                progress = s.progress
                statusText = s.status
            }
            is OnnxImageGenerationState.Complete -> outputPath = s.outputPath
            is OnnxImageGenerationState.Error -> error = s.message
            OnnxImageGenerationState.Idle -> Unit
        }
    }
    if (progress == null && error == null && outputPath == null) outputPath = latestPath
    if (progress == null && outputPath == null && error == null) return
    AppSectionCard {
        progress?.let { value ->
            Text(
                stringResource(R.string.easy_images_progress, statusText.orEmpty()),
                style = MaterialTheme.typography.bodyMedium
            )
            LinearProgressIndicator(progress = { value.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        error?.let {
            Text(
                stringResource(R.string.easy_images_error, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        outputPath?.let { path ->
            Text(
                stringResource(R.string.easy_images_result_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val bitmap by produceState<Bitmap?>(null, path) {
                value = withContext(Dispatchers.IO) { if (File(path).isFile) decodeSampled(path) else null }
            }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(R.string.easy_images_result_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().clickable { showViewer = true }
                )
                Text(
                    stringResource(R.string.easy_images_tap_to_enlarge),
                    style = MaterialTheme.typography.bodySmall
                )
                if (showViewer) {
                    Dialog(
                        onDismissRequest = { showViewer = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = stringResource(R.string.easy_images_result_desc),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.weight(1f).fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { showViewer = false },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            stringResource(R.string.easy_images_close),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            showViewer = false
                                            onOpenGallery()
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            stringResource(R.string.easy_images_open_gallery),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
