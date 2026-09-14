package com.example.llamadroid.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType

/**
 * Shows immutable inspector evidence beside the effective user selection.
 * Keeping this comparison in one composable prevents the promotion and
 * installed-model flows from silently presenting different semantics.
 */
@Composable
internal fun ModelClassificationComparisonCard(
    detectedFamily: String?,
    detectedType: String?,
    detectedRole: String?,
    selectedFamily: String,
    selectedType: String,
    selectedRole: String?
) {
    val detected = listOfNotNull(
        detectedFamily?.takeIf { it.isNotBlank() },
        detectedType?.takeIf { it.isNotBlank() },
        detectedRole?.takeIf { it.isNotBlank() }
    ).joinToString(" / ")
    val selected = listOfNotNull(
        selectedFamily.takeIf { it.isNotBlank() },
        selectedType.takeIf { it.isNotBlank() },
        selectedRole?.takeIf { it.isNotBlank() }
    ).joinToString(" / ")
    val detectedText = detected.ifBlank {
        stringResource(R.string.model_library_classification_unavailable)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.model_library_classification_title),
                style = MaterialTheme.typography.labelLarge
            )
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(
                            R.string.model_library_classification_detected,
                            detectedText
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.model_library_classification_selected, selected),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** Warning-only confirmation: semantic disagreement is allowed after review. */
@Composable
internal fun ModelClassificationMismatchDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.WarningAmber, contentDescription = null) },
        title = { Text(stringResource(R.string.model_library_classification_override_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.model_library_classification_override_body))
                Text(
                    stringResource(R.string.model_library_classification_integrity_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.model_library_classification_override_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.model_library_cancel))
            }
        }
    )
}

internal fun editableModelTypeOptions(): List<ModelType> = listOf(
    ModelType.LLM,
    ModelType.LLM_DRAFT,
    ModelType.LORA,
    ModelType.EMBEDDING,
    ModelType.VISION_PROJECTOR,
    ModelType.LLAMA_TTS,
    ModelType.LLAMA_TTS_COMPANION,
    ModelType.LITERT_AUDIO_DIT,
    ModelType.LITERT_AUDIO_COMPONENT
)

@Composable
internal fun modelTypeLabel(type: ModelType): String = when (type) {
    ModelType.LLM,
    ModelType.VISION -> stringResource(R.string.models_type_llm)
    ModelType.LLM_DRAFT -> stringResource(R.string.models_type_mtp)
    ModelType.LORA -> stringResource(R.string.models_type_lora)
    ModelType.EMBEDDING -> stringResource(R.string.models_type_embedding)
    ModelType.VISION_PROJECTOR,
    ModelType.MMPROJ -> stringResource(R.string.models_type_vision_projector)
    ModelType.LLAMA_TTS -> stringResource(R.string.model_promote_audio_tts)
    ModelType.LLAMA_TTS_COMPANION -> stringResource(R.string.model_promote_audio_tts_companion)
    ModelType.LITERT_AUDIO_DIT -> stringResource(R.string.model_library_role_stable_audio_dit)
    ModelType.LITERT_AUDIO_COMPONENT -> stringResource(R.string.model_library_role_stable_audio_component)
    else -> type.name
}

internal data class DetectedModelClassification(
    val type: ModelType?,
    val isVision: Boolean
)

internal fun detectedModelClassification(model: ModelEntity): DetectedModelClassification {
    val json = model.detectedClassificationJson
        ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    val type = json?.optString("type")
        ?.let { raw -> runCatching { ModelType.valueOf(raw) }.getOrNull() }
        ?.takeIf { it in editableModelTypeOptions() }
    val isVision = json?.optBoolean("isVision", false) == true ||
        json?.optString("role", "")?.contains("vision", ignoreCase = true) == true
    return DetectedModelClassification(type, isVision)
}

/** Shared controls for explicit installed-model type and capability edits. */
@Composable
internal fun InstalledModelClassificationControls(
    model: ModelEntity,
    editedModelType: ModelType,
    editedVisionSupport: Boolean,
    useForKnowledgeEmbedding: Boolean,
    onTypeChange: (ModelType) -> Unit,
    onVisionChange: (Boolean) -> Unit,
    onEmbeddingChange: (Boolean) -> Unit,
    onResetToDetected: (ModelType, Boolean) -> Unit
) {
    Text(stringResource(R.string.models_import_type_label), style = MaterialTheme.typography.labelMedium)
    editableModelTypeOptions().forEach { type ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = editedModelType == type,
                    onClick = { onTypeChange(type) }
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = editedModelType == type,
                onClick = { onTypeChange(type) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(modelTypeLabel(type))
        }
    }
    if (editedModelType == ModelType.EMBEDDING) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = useForKnowledgeEmbedding,
                onCheckedChange = onEmbeddingChange
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.models_use_for_kb_embedding))
        }
    }
    if (editedModelType == ModelType.LLM) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = editedVisionSupport,
                onCheckedChange = onVisionChange
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.models_vision_toggle_title))
                Text(
                    stringResource(R.string.models_vision_toggle_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (model.classificationSource.equals("USER_OVERRIDE", ignoreCase = true)) {
        Text(
            stringResource(R.string.model_library_manual_override),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
    val detected = remember(model.filename, model.detectedClassificationJson) {
        detectedModelClassification(model)
    }
    when (val detectedType = detected.type) {
        null -> Unit
        else -> {
            TextButton(
                onClick = { onResetToDetected(detectedType, detected.isVision) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.model_library_reset_to_detection))
            }
        }
    }
}
