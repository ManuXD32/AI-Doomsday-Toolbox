package com.example.llamadroid.ui.ai

import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.service.GeneratedVideoMetadata
import com.example.llamadroid.service.SdCacheMode
import com.example.llamadroid.service.VideoGenerationMode
import com.example.llamadroid.service.VideoRuntimeOptions
import com.example.llamadroid.sd.SdLoraApplyMode
import com.example.llamadroid.sd.SdLoraSpec
import com.example.llamadroid.sd.SdParamsBackendMode
import com.example.llamadroid.sd.SdRuntimeBackendMode
import com.example.llamadroid.sd.SdVideoComponentRole
import com.example.llamadroid.sd.SdVideoFamilyProfiles
import com.example.llamadroid.sd.SdVideoMainModelLayout
import com.example.llamadroid.sd.pathFor
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.videoComponentLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.llamadroid.media.addDistributedRuntimeLines
import com.example.llamadroid.media.generatedMediaSummaryLine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoLoraStackCard(
    models: List<ModelEntity>,
    loras: List<SdLoraSpec>,
    highNoiseLoras: List<SdLoraSpec>,
    applyMode: SdLoraApplyMode?,
    onLorasChange: (List<SdLoraSpec>) -> Unit,
    onHighNoiseLorasChange: (List<SdLoraSpec>) -> Unit,
    onApplyModeChange: (SdLoraApplyMode?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.sd_workflow_lora_stack_label),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                stringResource(R.string.video_gen_lora_stack_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (models.isEmpty()) {
                Text(
                    stringResource(R.string.imagegen_no_lora),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (loras.isNotEmpty()) {
                    Text(
                        stringResource(R.string.video_gen_lora_regular_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                loras.forEachIndexed { index, item ->
                    VideoLoraItemRow(
                        index = index,
                        item = item,
                        onStrengthChange = { strength ->
                            onLorasChange(loras.mapIndexed { itemIndex, current ->
                                if (itemIndex == index) current.copy(strength = strength) else current
                            })
                        },
                        onRemove = { onLorasChange(loras.filterIndexed { itemIndex, _ -> itemIndex != index }) }
                    )
                }
                ModelDropdown(
                    value = null,
                    placeholder = stringResource(R.string.video_gen_lora_add),
                    models = models,
                    onSelected = { model ->
                        onLorasChange(loras + SdLoraSpec(path = model.path))
                    }
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    stringResource(R.string.video_gen_lora_high_noise_label),
                    style = MaterialTheme.typography.labelLarge
                )
                highNoiseLoras.forEachIndexed { index, item ->
                    VideoLoraItemRow(
                        index = index,
                        item = item,
                        onStrengthChange = { strength ->
                            onHighNoiseLorasChange(highNoiseLoras.mapIndexed { itemIndex, current ->
                                if (itemIndex == index) current.copy(strength = strength, highNoiseOnly = true) else current
                            })
                        },
                        onRemove = {
                            onHighNoiseLorasChange(highNoiseLoras.filterIndexed { itemIndex, _ -> itemIndex != index })
                        }
                    )
                }
                ModelDropdown(
                    value = null,
                    placeholder = stringResource(R.string.video_gen_lora_add_high_noise),
                    models = models,
                    onSelected = { model ->
                        onHighNoiseLorasChange(highNoiseLoras + SdLoraSpec(path = model.path, highNoiseOnly = true))
                    }
                )
                Text(
                    stringResource(R.string.imagegen_lora_apply_mode_label),
                    style = MaterialTheme.typography.labelLarge
                )
                var applyExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = applyExpanded,
                    onExpandedChange = { applyExpanded = !applyExpanded }
                ) {
                    OutlinedTextField(
                        value = applyMode?.cliName ?: stringResource(R.string.imagegen_lora_apply_mode_default),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = applyExpanded)
                        },
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = applyExpanded,
                        onDismissRequest = { applyExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.imagegen_lora_apply_mode_default)) },
                            onClick = {
                                onApplyModeChange(null)
                                applyExpanded = false
                            }
                        )
                        SdLoraApplyMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.cliName) },
                                onClick = {
                                    onApplyModeChange(mode)
                                    applyExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoLoraItemRow(
    index: Int,
    item: SdLoraSpec,
    onStrengthChange: (Float) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${index + 1}. ${item.filename}",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.imagegen_lora_remove))
            }
        }
        androidx.compose.material3.Slider(
            value = item.strength,
            onValueChange = onStrengthChange,
            valueRange = SdLoraSpec.MIN_STRENGTH..SdLoraSpec.MAX_STRENGTH,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = String.format(Locale.US, "%.2f", item.strength),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelDropdown(
    value: String?,
    placeholder: String,
    models: List<ModelEntity>,
    onSelected: (ModelEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = value?.substringAfterLast("/") ?: placeholder,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            models.forEach { model ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(model.filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelected(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
internal fun OptionalModelCard(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    models: List<ModelEntity>,
    selectedPath: String?,
    emptyText: String,
    placeholder: String,
    onSelected: (ModelEntity) -> Unit,
    onGetModels: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                if (models.isEmpty()) {
                    Text(
                        emptyText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onGetModels) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.video_gen_get_models))
                    }
                } else {
                    ModelDropdown(
                        value = selectedPath,
                        placeholder = placeholder,
                        models = models,
                        onSelected = onSelected
                    )
                }
            }
        }
    }
}

@Composable
internal fun VideoNumberField(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    VideoTextField(
        modifier = modifier,
        label = label,
        value = value,
        keyboardType = KeyboardType.Number,
        onValueChange = onValueChange
    )
}

@Composable
internal fun VideoTextField(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoLocalSdCliMemoryControls(
    selectedModel: ModelEntity?,
    maxRamEnabled: Boolean,
    maxRamGiB: String,
    onParamsBackendChange: (SdParamsBackendMode) -> Unit,
    onRuntimeBackendChange: (SdRuntimeBackendMode) -> Unit,
    onMaxRamEnabledChange: (Boolean) -> Unit,
    onMaxRamGiBChange: (String) -> Unit
) {
    val paramsMode = SdParamsBackendMode.fromStoredValue(selectedModel?.sdParamsBackendMode)
    val runtimeMode = SdRuntimeBackendMode.fromStoredValue(selectedModel?.sdRuntimeBackendMode)
    Text(
        stringResource(R.string.sd_models_local_backend_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Text(
        stringResource(R.string.imagegen_local_sd_memory_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VideoSdParamsBackendDropdown(
            modifier = Modifier.weight(1f),
            value = paramsMode,
            enabled = selectedModel != null,
            onValueChange = onParamsBackendChange
        )
        VideoSdRuntimeBackendDropdown(
            modifier = Modifier.weight(1f),
            value = runtimeMode,
            enabled = selectedModel != null,
            onValueChange = onRuntimeBackendChange
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.imagegen_max_cpu_ram_toggle),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = maxRamEnabled,
            onCheckedChange = onMaxRamEnabledChange
        )
    }
    Text(
        stringResource(R.string.imagegen_max_cpu_ram_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (maxRamEnabled) {
        Spacer(modifier = Modifier.height(8.dp))
        VideoTextField(
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.imagegen_max_cpu_ram_label),
            value = maxRamGiB,
            keyboardType = KeyboardType.Decimal,
            onValueChange = onMaxRamGiBChange
        )
        Text(
            stringResource(R.string.imagegen_max_cpu_ram_support),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoSdParamsBackendDropdown(
    modifier: Modifier = Modifier,
    value: SdParamsBackendMode,
    enabled: Boolean,
    onValueChange: (SdParamsBackendMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = videoSdParamsBackendModeLabel(value),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text(stringResource(R.string.sd_models_params_backend_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SdParamsBackendMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(videoSdParamsBackendModeLabel(mode)) },
                    onClick = {
                        onValueChange(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoSdRuntimeBackendDropdown(
    modifier: Modifier = Modifier,
    value: SdRuntimeBackendMode,
    enabled: Boolean,
    onValueChange: (SdRuntimeBackendMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = videoSdRuntimeBackendModeLabel(value),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text(stringResource(R.string.sd_models_runtime_backend_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SdRuntimeBackendMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(videoSdRuntimeBackendModeLabel(mode)) },
                    onClick = {
                        onValueChange(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
internal fun videoSdParamsBackendModeLabel(mode: SdParamsBackendMode): String = when (mode) {
    SdParamsBackendMode.AUTO -> stringResource(R.string.sd_models_backend_auto)
    SdParamsBackendMode.DISK -> stringResource(R.string.sd_models_params_backend_disk)
}

@Composable
internal fun videoSdRuntimeBackendModeLabel(mode: SdRuntimeBackendMode): String = when (mode) {
    SdRuntimeBackendMode.AUTO -> stringResource(R.string.sd_models_backend_auto)
    SdRuntimeBackendMode.CPU -> stringResource(R.string.sd_models_runtime_backend_cpu)
}

@Composable
internal fun rememberVideoPreviewBitmap(path: String?): androidx.compose.runtime.State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = path?.let { previewPath ->
            withContext(Dispatchers.IO) {
                android.graphics.BitmapFactory.decodeFile(previewPath)?.asImageBitmap()
            }
        }
    }

@Composable
fun VideoGalleryCard(
    metadata: GeneratedVideoMetadata,
    onClick: () -> Unit
) {
    var thumbnailLoaded by remember(metadata.preferredArtifactPath) { mutableStateOf(false) }
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, metadata.preferredArtifactPath) {
        value = withContext(Dispatchers.IO) {
            createVideoThumbnail(metadata.preferredArtifactPath)
        }
        thumbnailLoaded = true
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio((metadata.width.toFloat() / metadata.height.coerceAtLeast(1)).coerceIn(1.2f, 2.4f))
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!,
                        contentDescription = stringResource(
                            R.string.soft_studio_generated_video_description,
                            File(metadata.preferredArtifactPath).name
                        ),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (!thumbnailLoaded) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.video_output_open_preview))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                VideoModeBadge(metadata.modeEnum)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    metadata.promptSnippet(96),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    stringResource(R.string.video_output_summary, metadata.width, metadata.height, metadata.videoFrames, metadata.fps, metadata.steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    File(metadata.diffusionModelName).name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun VideoModeBadge(mode: VideoGenerationMode) {
    val color = if (mode == VideoGenerationMode.TXT2VID) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = if (mode == VideoGenerationMode.TXT2VID) {
                stringResource(R.string.video_gen_mode_txt2vid)
            } else {
                stringResource(R.string.video_gen_mode_img2vid)
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
internal fun VideoDetailDialog(
    metadata: GeneratedVideoMetadata,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onInterpolate: () -> Unit,
    onUpscale: () -> Unit,
    onInterpolateAndUpscale: () -> Unit,
    onCopyInfo: () -> Unit,
    onRetryConversion: () -> Unit,
    onDelete: () -> Unit,
    onReuse: () -> Unit
) {
    VideoDetailSurface(
        onDismiss = onDismiss,
        title = {
            Column {
                VideoModeBadge(metadata.modeEnum)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.video_gen_generated_title))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()

            ) {
                if (File(metadata.preferredArtifactPath).extension.equals("webp", true)) {
                    NativeAnimatedVideoPreview(metadata.preferredArtifactPath)
                } else {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio((metadata.width.toFloat() / metadata.height.coerceAtLeast(1)).coerceIn(0.6f, 2.4f))
                        .clip(RoundedCornerShape(12.dp)),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            tag = metadata.preferredArtifactPath
                            setVideoURI(Uri.fromFile(File(metadata.preferredArtifactPath)))
                            setOnPreparedListener { player ->
                                player.isLooping = true
                                start()
                            }
                        }
                    },
                    onRelease = { view -> view.stopPlayback() },
                    update = { view ->
                        if (view.tag != metadata.preferredArtifactPath) {
                            view.tag = metadata.preferredArtifactPath
                            view.setVideoURI(Uri.fromFile(File(metadata.preferredArtifactPath)))
                        }
                    }
                )
                }
                Spacer(modifier = Modifier.height(12.dp))
                VideoPromptBlock(stringResource(R.string.video_detail_positive_prompt), metadata.prompt)
                if (metadata.negativePrompt.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    VideoPromptBlock(stringResource(R.string.video_gen_negative_prompt_label), metadata.negativePrompt)
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                val detailContext = LocalContext.current
                val summaryLine = remember(metadata.metadataPath) {
                    generatedMediaSummaryLine(detailContext, metadata.generationDurationMs, metadata.distributedRuntime)
                }
                ParameterLine(summaryLine.first, summaryLine.second)
                AppAdvancedSection(title = stringResource(R.string.video_detail_parameters)) {
                ParameterLine(stringResource(R.string.video_gen_model_label), File(metadata.diffusionModelName).name)
                ParameterLine(stringResource(R.string.video_controls_seed), metadata.seed.toString())
                SdVideoComponentRole.entries.filter { it != SdVideoComponentRole.LORA }.forEach { role ->
                    metadata.videoComponents.pathFor(role)?.takeIf(String::isNotBlank)?.let { path ->
                        ParameterLine(videoComponentLabel(role), File(path).name)
                    }
                }
                (metadata.loras + metadata.highNoiseLoras).forEach { lora ->
                    ParameterLine(lora.filename, stringResource(R.string.video_detail_lora_summary,
                        lora.strength.toString(), stringResource(if (lora.enabled) R.string.video_gen_enabled else R.string.video_gen_disabled),
                        stringResource(if (lora.highNoiseOnly || lora in metadata.highNoiseLoras) R.string.video_controls_lora_high_noise else R.string.video_controls_lora_regular)))
                }
                ParameterLine(stringResource(R.string.video_gen_frames_label), metadata.videoFrames.toString())
                ParameterLine(stringResource(R.string.video_gen_fps_label), metadata.fps.toString())
                ParameterLine(stringResource(R.string.video_gen_width_label), metadata.width.toString())
                ParameterLine(stringResource(R.string.video_gen_height_label), metadata.height.toString())
                ParameterLine(stringResource(R.string.video_gen_steps_label), metadata.steps.toString())
                ParameterLine(stringResource(R.string.video_gen_cfg_scale_label), metadata.cfgScale.toString())
                metadata.generationDurationMs?.let {
                    ParameterLine(
                        stringResource(R.string.sd_dist_gallery_total_time_label),
                        formatVideoGenerationDuration(it)
                    )
                }
                ParameterLine(stringResource(R.string.sd_dist_gallery_created_label), formatVideoGalleryDate(metadata.createdAt))
                metadata.conditioningDurationMs?.let { ParameterLine(stringResource(R.string.sd_stage_conditioning_label), formatVideoGenerationDuration(it)) }
                metadata.samplingDurationMs?.let { ParameterLine(stringResource(R.string.sd_stage_sampling_label), formatVideoGenerationDuration(it)) }
                metadata.decodingDurationMs?.let { ParameterLine(stringResource(R.string.sd_stage_decoding_label), formatVideoGenerationDuration(it)) }
                metadata.flowShift?.let {
                    ParameterLine(stringResource(R.string.video_gen_flow_shift_label), it.toString())
                }
                ParameterLine(stringResource(R.string.video_gen_sampler_label), metadata.samplingMethod.cliName)
                ParameterLine(
                    stringResource(R.string.gen_cache_mode_label),
                    metadata.cacheMode?.cliName ?: stringResource(R.string.gen_cache_mode_off)
                )
                if (metadata.cacheOption.isNotBlank()) {
                    ParameterLine(stringResource(R.string.gen_cache_option_label), metadata.cacheOption)
                }
                if (metadata.cacheMode == SdCacheMode.CACHE_DIT) {
                    metadata.scmPolicy?.let {
                        ParameterLine(stringResource(R.string.gen_cache_scm_policy_label), it.cliName)
                    }
                    if (metadata.scmMask.isNotBlank()) {
                        ParameterLine(stringResource(R.string.gen_cache_scm_mask_label), metadata.scmMask)
                    }
                }
                ParameterLine(stringResource(R.string.video_gen_threads_label), metadata.threads.toString())
                ParameterLine(stringResource(R.string.video_gen_vae_toggle_label), if (metadata.vaeEnabled) (metadata.vaeName ?: "-") else stringResource(R.string.video_gen_disabled))
                ParameterLine(stringResource(R.string.video_gen_t5_toggle_label), if (metadata.t5xxlEnabled) (metadata.t5xxlName ?: "-") else stringResource(R.string.video_gen_disabled))
                ParameterLine(stringResource(R.string.video_gen_vae_tiling_label), if (metadata.vaeTiling) stringResource(R.string.video_gen_enabled) else stringResource(R.string.video_gen_disabled))
                if (metadata.vaeTiling && !metadata.vaeTileSize.isNullOrBlank()) {
                    ParameterLine(stringResource(R.string.video_gen_vae_tile_size_label), metadata.vaeTileSize)
                }
                ParameterLine(stringResource(R.string.video_gen_diffusion_fa_label), if (metadata.diffusionFa) stringResource(R.string.video_gen_enabled) else stringResource(R.string.video_gen_disabled))
                ParameterLine(stringResource(R.string.imagegen_diffusion_conv_direct_label), if (metadata.diffusionConvDirect) stringResource(R.string.video_gen_enabled) else stringResource(R.string.video_gen_disabled))
                ParameterLine(stringResource(R.string.imagegen_vae_conv_direct_label), if (metadata.vaeConvDirect) stringResource(R.string.video_gen_enabled) else stringResource(R.string.video_gen_disabled))
                ParameterLine(stringResource(R.string.video_gen_mmap_label), if (metadata.mmap) stringResource(R.string.video_gen_enabled) else stringResource(R.string.video_gen_disabled))
                ParameterLine(stringResource(R.string.sd_models_params_backend_label), metadata.sdParamsBackendMode)
                ParameterLine(stringResource(R.string.sd_models_runtime_backend_label), metadata.sdRuntimeBackendMode)
                if (metadata.maxVramCpuGiB.isNotBlank()) {
                    ParameterLine(stringResource(R.string.imagegen_max_cpu_ram_label), metadata.maxVramCpuGiB)
                }
                metadata.initImagePath?.let {
                    ParameterLine(stringResource(R.string.video_gen_input_image_title), File(it).name)
                }
                val distributedLines = remember(metadata.metadataPath) {
                    mutableListOf<Pair<String, String>>().also {
                        addDistributedRuntimeLines(detailContext, it, metadata.distributedRuntime)
                    }
                }
                distributedLines.forEach { (label, value) -> ParameterLine(label, value) }
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReuse, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.video_detail_reuse))
                }
                if (metadata.conversionRecoveredNative) {
                    OutlinedButton(onClick = onRetryConversion, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.video_output_retry_conversion))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onInterpolate) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.video_gen_action_interpolate))
                    }
                    OutlinedButton(onClick = onUpscale) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.video_gen_action_upscale))
                    }
                }
                Button(onClick = onInterpolateAndUpscale, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.video_gen_action_interpolate_upscale))
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCopyInfo) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.video_gen_copy_info))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_share))
                    }
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_delete))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    )
}

@Composable
internal fun ParameterLine(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

internal fun createVideoThumbnail(path: String): ImageBitmap? {
    if (File(path).extension.equals("webp", ignoreCase = true)) {
        return decodeBoundedVideoImage(path)?.asImageBitmap()
    }
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.getFrameAtTime(0)?.asImageBitmap()
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

internal fun deleteDocumentUri(context: Context, uriString: String) {
    val uri = uriString.toUri()
    val document = DocumentFile.fromSingleUri(context, uri)
    if (document?.delete() != true) {
        context.contentResolver.delete(uri, null, null)
    }
}

internal fun stringResourceSafe(context: Context, resId: Int): String = context.getString(resId)

internal fun videoMimeType(file: File): String = when (file.extension.lowercase(Locale.US)) {
    "avi" -> "video/x-msvideo"
    "webm" -> "video/webm"
    "webp" -> "image/webp"
    else -> "video/mp4"
}

internal fun buildVideoGenerationInfoText(context: Context, metadata: GeneratedVideoMetadata): String {
    val lines = mutableListOf(
        "${context.getString(R.string.video_gen_mode_label)}: ${formatVideoModeLabel(context, metadata.modeEnum)}",
        "${context.getString(R.string.video_gen_prompt_label)}: ${metadata.prompt}",
        "${context.getString(R.string.video_gen_model_label)}: ${metadata.diffusionModelName}",
        "${context.getString(R.string.video_gen_frames_label)}: ${metadata.videoFrames}",
        "${context.getString(R.string.video_gen_fps_label)}: ${metadata.fps}",
        "${context.getString(R.string.video_gen_width_label)}: ${metadata.width}",
        "${context.getString(R.string.video_gen_height_label)}: ${metadata.height}",
        "${context.getString(R.string.video_gen_steps_label)}: ${metadata.steps}",
        "${context.getString(R.string.video_gen_cfg_scale_label)}: ${metadata.cfgScale}",
        "${context.getString(R.string.video_gen_sampler_label)}: ${metadata.samplingMethod.cliName}",
        "${context.getString(R.string.gen_cache_mode_label)}: ${metadata.cacheMode?.cliName ?: context.getString(R.string.gen_cache_mode_off)}",
        "${context.getString(R.string.video_gen_threads_label)}: ${metadata.threads}",
        "${context.getString(R.string.video_gen_vae_toggle_label)}: ${if (metadata.vaeEnabled) (metadata.vaeName ?: "-") else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.video_gen_t5_toggle_label)}: ${if (metadata.t5xxlEnabled) (metadata.t5xxlName ?: "-") else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.video_gen_vae_tiling_label)}: ${if (metadata.vaeTiling) context.getString(R.string.video_gen_enabled) else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.video_gen_diffusion_fa_label)}: ${if (metadata.diffusionFa) context.getString(R.string.video_gen_enabled) else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.imagegen_diffusion_conv_direct_label)}: ${if (metadata.diffusionConvDirect) context.getString(R.string.video_gen_enabled) else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.imagegen_vae_conv_direct_label)}: ${if (metadata.vaeConvDirect) context.getString(R.string.video_gen_enabled) else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.video_gen_mmap_label)}: ${if (metadata.mmap) context.getString(R.string.video_gen_enabled) else context.getString(R.string.video_gen_disabled)}",
        "${context.getString(R.string.sd_models_params_backend_label)}: ${metadata.sdParamsBackendMode}",
        "${context.getString(R.string.sd_models_runtime_backend_label)}: ${metadata.sdRuntimeBackendMode}"
    )
    lines.add("${context.getString(R.string.sd_dist_gallery_created_label)}: ${formatVideoGalleryDate(metadata.createdAt)}")

    if (metadata.negativePrompt.isNotBlank()) {
        lines.add(2, "${context.getString(R.string.video_gen_negative_prompt_label)}: ${metadata.negativePrompt}")
    }
    metadata.generationDurationMs?.let {
        lines.add("${context.getString(R.string.sd_dist_gallery_total_time_label)}: ${formatVideoGenerationDuration(it)}")
    }
    metadata.conditioningDurationMs?.let { lines.add("${context.getString(R.string.sd_stage_conditioning, formatVideoGenerationDuration(it))}") }
    metadata.samplingDurationMs?.let { lines.add("${context.getString(R.string.sd_stage_sampling, formatVideoGenerationDuration(it))}") }
    metadata.decodingDurationMs?.let { lines.add("${context.getString(R.string.sd_stage_decoding, formatVideoGenerationDuration(it))}") }
    if (metadata.vaeTiling && !metadata.vaeTileSize.isNullOrBlank()) {
        lines.add("${context.getString(R.string.video_gen_vae_tile_size_label)}: ${metadata.vaeTileSize}")
    }
    metadata.flowShift?.let {
        lines.add("${context.getString(R.string.video_gen_flow_shift_label)}: $it")
    }
    if (metadata.cacheOption.isNotBlank()) {
        lines.add("${context.getString(R.string.gen_cache_option_label)}: ${metadata.cacheOption}")
    }
    metadata.scmPolicy?.let {
        lines.add("${context.getString(R.string.gen_cache_scm_policy_label)}: ${it.cliName}")
    }
    if (metadata.scmMask.isNotBlank()) {
        lines.add("${context.getString(R.string.gen_cache_scm_mask_label)}: ${metadata.scmMask}")
    }
    if (metadata.maxVramCpuGiB.isNotBlank()) {
        lines.add("${context.getString(R.string.imagegen_max_cpu_ram_label)}: ${metadata.maxVramCpuGiB}")
    }
    metadata.initImagePath?.let {
        lines.add("${context.getString(R.string.video_gen_input_image_title)}: ${File(it).name}")
    }

    return lines.joinToString(separator = "\n")
}

internal fun formatVideoGenerationDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

internal fun formatVideoGalleryDate(timestampMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

internal const val VIDEO_DRAFT_PERSIST_DEBOUNCE_MS = 400L

internal data class VideoPathReference(
    val path: String,
    val directory: Boolean = false
)

/**
 * Return the main model path in the same order used by the typed video command.
 * Full-model families must keep their checkpoint path ahead of any legacy scalar.
 */
internal fun videoMainModelPathForSelection(options: VideoRuntimeOptions): String? {
    val profile = options.videoFamily?.let { family ->
        runCatching { SdVideoFamilyProfiles.resolve(family, options.videoVariant) }.getOrNull()
    }
    return when (profile?.mainModelLayout ?: SdVideoMainModelLayout.STANDALONE_DIFFUSION) {
        SdVideoMainModelLayout.FULL_MODEL -> options.videoComponents.fullModelPath
            ?: options.videoComponents.diffusionModelPath
        SdVideoMainModelLayout.STANDALONE_DIFFUSION -> options.videoComponents.diffusionModelPath
            ?: options.videoComponents.fullModelPath
    }
}

/** Match the paths emitted by VideoCommandBuilder before starting generation. */
internal fun videoGenerationPathReferences(
    options: VideoRuntimeOptions,
    selectedModelPath: String?,
    loras: List<SdLoraSpec>,
    highNoiseLoras: List<SdLoraSpec>
): List<VideoPathReference> {
    val profile = options.videoFamily?.let { family ->
        runCatching { SdVideoFamilyProfiles.resolve(family, options.videoVariant) }.getOrNull()
    }
    val components = options.videoComponents
    val inputs = options.videoInputs

    return buildList {
        fun addFile(path: String?) {
            path?.takeIf(String::isNotBlank)?.let { add(VideoPathReference(it)) }
        }

        fun addDirectory(path: String?) {
            path?.takeIf(String::isNotBlank)?.let { add(VideoPathReference(it, directory = true)) }
        }

        val mainModelPath = when (profile?.mainModelLayout ?: SdVideoMainModelLayout.STANDALONE_DIFFUSION) {
            SdVideoMainModelLayout.FULL_MODEL -> components.fullModelPath
                ?: components.diffusionModelPath
                ?: selectedModelPath
            SdVideoMainModelLayout.STANDALONE_DIFFUSION -> components.diffusionModelPath
                ?: selectedModelPath
        }
        addFile(mainModelPath)
        addFile(components.highNoiseDiffusionModelPath)
        addFile(components.uncondDiffusionModelPath)

        // The command emits one decoder. Check only the selected branch so an old,
        // unused alternate path cannot block a valid run.
        val decoderTae = components.taePath?.takeIf {
            options.useTae || components.vaePath.isNullOrBlank()
        }
        if (decoderTae != null) addFile(decoderTae) else addFile(components.vaePath)

        addFile(components.t5xxlPath)
        addFile(components.llmPath)
        addFile(components.llmVisionPath)
        addFile(components.audioVaePath)
        addFile(components.embeddingsConnectorsPath)
        addFile(components.motionModulePath)
        addFile(components.clipVisionPath)
        addFile(components.ipAdapterPath)
        addFile(inputs.ipAdapterImagePath)
        addFile(components.controlNetPath)

        if (options.hires.enabled) {
            addDirectory(components.hiresUpscalersDir)
            addFile(components.hiresUpscaler)
        }

        loras.filter { it.enabled }.forEach { addFile(it.path) }
        highNoiseLoras.filter { it.enabled }.forEach { addFile(it.path) }

        addFile(inputs.initImagePath)
        addFile(inputs.endImagePath)
        addFile(inputs.controlImagePath)
        addDirectory(inputs.controlVideoPath)
        inputs.referenceImages.forEach(::addFile)
        inputs.referenceVideos.forEach(::addDirectory)
        inputs.referenceVideoAudios.forEach(::addFile)
        inputs.referenceAudios.forEach(::addFile)
    }.distinct()
}

internal fun videoPathReferenceAvailable(reference: VideoPathReference): Boolean =
    File(reference.path).let { file ->
        if (reference.directory) {
            file.isDirectory && file.canRead()
        } else {
            file.isFile && file.canRead()
        }
    }

internal fun formatVideoModeLabel(context: Context, mode: VideoGenerationMode): String {
    return when (mode) {
        VideoGenerationMode.TXT2VID -> context.getString(R.string.video_gen_mode_txt2vid)
        VideoGenerationMode.IMG2VID -> context.getString(R.string.video_gen_mode_img2vid)
    }
}

@Composable
internal fun buildVideoGenerationHelpSections(selectedMode: Int): List<GenerationOptionHelpSection> {
    val modeTitle = if (selectedMode == 1) {
        stringResource(R.string.video_gen_mode_img2vid)
    } else {
        stringResource(R.string.video_gen_mode_txt2vid)
    }
    val modeBody = if (selectedMode == 1) {
        stringResource(R.string.video_gen_help_img2vid_body)
    } else {
        stringResource(R.string.video_gen_help_txt2vid_body)
    }

    val sections = mutableListOf(
        GenerationOptionHelpSection(
            title = modeTitle,
            body = modeBody
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.video_gen_help_models_title),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_model_label),
                    stringResource(R.string.video_gen_help_model_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_vae_toggle_label),
                    stringResource(R.string.video_gen_help_vae_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_t5_toggle_label),
                    stringResource(R.string.video_gen_help_t5_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.video_gen_help_prompting_title),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_prompt_label),
                    stringResource(R.string.video_gen_help_prompt_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_negative_prompt_label),
                    stringResource(R.string.video_gen_help_negative_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_input_image_title),
                    stringResource(R.string.video_gen_help_input_image_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.video_gen_parameters_title),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_frames_label) + " / " + stringResource(R.string.video_gen_fps_label),
                    stringResource(R.string.video_gen_help_timing_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_width_label) + " / " + stringResource(R.string.video_gen_height_label),
                    stringResource(R.string.video_gen_help_size_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_steps_label) + " / " + stringResource(R.string.video_gen_cfg_scale_label),
                    stringResource(R.string.video_gen_help_steps_cfg_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_sampler_label),
                    stringResource(R.string.video_gen_help_sampler_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_threads_label),
                    stringResource(R.string.video_gen_help_threads_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_flow_shift_toggle_label) + " / " + stringResource(R.string.video_gen_flow_shift_label),
                    stringResource(R.string.video_gen_help_flow_shift_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_vae_tiling_label) + " / " + stringResource(R.string.video_gen_vae_tile_size_label),
                    stringResource(R.string.video_gen_help_vae_tiling_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.video_gen_diffusion_fa_label) + " / " + stringResource(R.string.video_gen_mmap_label),
                    stringResource(R.string.video_gen_help_runtime_flags_desc)
                )
            )
        ),
        GenerationOptionHelpSection(
            title = stringResource(R.string.gen_cache_title),
            items = listOf(
                GenerationOptionHelpItem(
                    stringResource(R.string.gen_cache_mode_label),
                    stringResource(R.string.video_gen_help_cache_mode_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.gen_cache_option_label),
                    stringResource(R.string.video_gen_help_cache_option_desc)
                ),
                GenerationOptionHelpItem(
                    stringResource(R.string.gen_cache_scm_policy_label) + " / " + stringResource(R.string.gen_cache_scm_mask_label),
                    stringResource(R.string.video_gen_help_cache_scm_desc)
                )
            )
        )
    )

    return sections
}

@Composable
internal fun NativeAnimatedVideoPreview(path: String) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val description = stringResource(R.string.soft_studio_generated_video_description, File(path).name)
    val drawable by produceState<android.graphics.drawable.Drawable?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                        val scale = minOf(1f, 1080f / info.size.width, 660f / info.size.height)
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                } else decodeBoundedVideoImage(path)?.let { it.toDrawable(resources) }
            }.getOrNull()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        factory = { android.widget.ImageView(it).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
        update = { view ->
            view.contentDescription = description
            if (view.drawable !== drawable) {
                view.setImageDrawable(drawable)
                (drawable as? android.graphics.drawable.Animatable)?.start()
            }
        },
        onRelease = { view -> (view.drawable as? android.graphics.drawable.Animatable)?.stop() }
    )
}

/** Bound still previews on API 26/27 and thumbnails to avoid decoding full-size frames. */
internal fun decodeBoundedVideoImage(path: String): android.graphics.Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1080 || bounds.outHeight / sample > 660) sample *= 2
    android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

@Composable
internal fun VideoBooleanOption(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}
