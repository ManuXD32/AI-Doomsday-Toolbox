package com.example.llamadroid.ui.ai

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.service.SdIpAdapterReferenceStore
import com.example.llamadroid.ui.components.SliderWithInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdIpAdapterCard(
    supported: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    adapterModels: List<ModelEntity>,
    clipVisionModels: List<ModelEntity>,
    selectedAdapterPath: String?,
    onAdapterPathChange: (String?) -> Unit,
    selectedClipVisionPath: String?,
    onClipVisionPathChange: (String?) -> Unit,
    referenceImagePath: String?,
    onReferenceImagePathChange: (String?) -> Unit,
    strength: Float,
    onStrengthChange: (Float) -> Unit,
    onClearConfiguration: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var importingImage by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    val referencePreview by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = referenceImagePath
    ) {
        value = withContext(Dispatchers.IO) {
            referenceImagePath
                ?.let(::File)
                ?.takeIf(SdIpAdapterReferenceStore::isReadableImage)
                ?.let(::decodeSdIpAdapterPreview)
                ?.asImageBitmap()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importingImage = true
                importError = null
                runCatching {
                    SdIpAdapterReferenceStore.importImage(
                        context = context,
                        sourceUri = uri
                    )
                }.onSuccess { file ->
                    onReferenceImagePathChange(file.absolutePath)
                }.onFailure { error ->
                    importError = context.getString(
                        R.string.imagegen_ip_adapter_reference_import_failed,
                        error.message ?: context.getString(R.string.error_generic)
                    )
                }
                importingImage = false
            }
        }
    }

    val adapterCompatible = adapterModels.any { it.path == selectedAdapterPath }
    val clipVisionCompatible = clipVisionModels.any { it.path == selectedClipVisionPath }
    val ready = supported &&
        adapterCompatible &&
        clipVisionCompatible &&
        selectedAdapterPath?.let { File(it).isFile } == true &&
        selectedClipVisionPath?.let { File(it).isFile } == true &&
        referenceImagePath?.let { SdIpAdapterReferenceStore.isReadableImage(File(it)) } == true

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.imagegen_ip_adapter_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when {
                            !supported -> stringResource(R.string.imagegen_ip_adapter_unsupported_summary)
                            enabled && ready -> stringResource(R.string.imagegen_ip_adapter_ready)
                            enabled -> stringResource(R.string.imagegen_ip_adapter_incomplete)
                            else -> stringResource(R.string.imagegen_ip_adapter_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled && (!supported || !ready)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = enabled,
                    enabled = supported || enabled,
                    onCheckedChange = { requested ->
                        if (!requested || supported) onEnabledChange(requested)
                    }
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.imagegen_ip_adapter_compatibility_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!supported) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.imagegen_error_ip_adapter_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                SdIpAdapterModelPicker(
                    label = stringResource(R.string.imagegen_ip_adapter_model_label),
                    models = adapterModels,
                    selectedPath = selectedAdapterPath,
                    enabled = enabled && supported,
                    emptyText = stringResource(R.string.imagegen_ip_adapter_no_adapter_models),
                    onSelected = onAdapterPathChange
                )
                Spacer(modifier = Modifier.height(10.dp))
                SdIpAdapterModelPicker(
                    label = stringResource(R.string.imagegen_ip_adapter_clip_vision_label),
                    models = clipVisionModels,
                    selectedPath = selectedClipVisionPath,
                    enabled = enabled && supported,
                    emptyText = stringResource(R.string.imagegen_ip_adapter_no_clip_vision_models),
                    onSelected = onClipVisionPathChange
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.imagegen_ip_adapter_reference_label),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(6.dp))
                referencePreview?.let { preview ->
                    Image(
                        bitmap = preview,
                        contentDescription = stringResource(R.string.imagegen_ip_adapter_reference_label),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(preview.width.toFloat() / preview.height.coerceAtLeast(1)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { imagePicker.launch(arrayOf("image/*")) },
                        enabled = enabled && supported && !importingImage,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (importingImage) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(
                                if (referenceImagePath == null) {
                                    R.string.imagegen_ip_adapter_choose_reference
                                } else {
                                    R.string.imagegen_ip_adapter_replace_reference
                                }
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (referenceImagePath != null) {
                        IconButton(
                            onClick = { onReferenceImagePathChange(null) },
                            enabled = enabled && supported
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_clear)
                            )
                        }
                    }
                }
                importError?.let { message ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                SliderWithInput(
                    value = strength,
                    onValueChange = onStrengthChange,
                    valueRange = 0f..2f,
                    label = stringResource(R.string.imagegen_ip_adapter_strength),
                    decimalPlaces = 2,
                    enabled = enabled && supported
                )
                Text(
                    stringResource(R.string.imagegen_ip_adapter_strength_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenModels,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.imagegen_ip_adapter_manage_models),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onClearConfiguration,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.imagegen_ip_adapter_clear))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SdIpAdapterModelPicker(
    label: String,
    models: List<ModelEntity>,
    selectedPath: String?,
    enabled: Boolean,
    emptyText: String,
    onSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled && models.isNotEmpty()) expanded = it }
    ) {
        OutlinedTextField(
            value = selectedPath?.substringAfterLast('/') ?: emptyText,
            onValueChange = {},
            readOnly = true,
            enabled = enabled && models.isNotEmpty(),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.filename, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            model.sdCompatProfiles?.takeIf { it.isNotBlank() }?.let { profiles ->
                                Text(
                                    profiles,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelected(model.path)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun decodeSdIpAdapterPreview(file: File): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
}
