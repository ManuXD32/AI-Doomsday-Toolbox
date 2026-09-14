package com.example.llamadroid.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.model.PortableModelMetadata
import com.example.llamadroid.data.model.StableAudioModelSupport
import com.example.llamadroid.data.model.library.ModelClassificationPolicy
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.sd.SdArtifactInspection
import org.json.JSONObject

@Composable
internal fun modelFamilyLabel(family: ModelFamily): String = when (family) {
    ModelFamily.LLM -> stringResource(R.string.model_library_family_llm)
    ModelFamily.SD -> stringResource(R.string.model_library_family_sd)
    ModelFamily.ONNX -> stringResource(R.string.model_library_family_onnx)
    ModelFamily.LITERT -> stringResource(R.string.model_library_family_litert)
    ModelFamily.WHISPER -> stringResource(R.string.model_library_family_whisper)
    ModelFamily.AUDIO -> stringResource(R.string.model_library_family_audio)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromoteArtifactDialog(
    artifact: PendingModelArtifactEntity,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPromote: (ModelFamily, String, String?, String) -> Unit,
    savedMetadataJson: String? = null
) {
    val savedMetadata = remember(artifact.id, savedMetadataJson) {
        JSONObject(PortableModelMetadata.sanitize(savedMetadataJson))
    }
    var family by remember(artifact.id) {
        mutableStateOf(ModelFamily.fromStoredValue(if (artifact.bundleId != null)
            artifact.requestedFamily ?: artifact.detectedFamily else artifact.detectedFamily ?: artifact.requestedFamily) ?: ModelFamily.LLM)
    }
    var name by remember(artifact.id) { mutableStateOf(artifact.filename.substringBeforeLast('.')) }
    val typeChoices = remember(family) { modelPromotionChoices(family) }
    var choiceId by remember(artifact.id, family, savedMetadataJson) {
        mutableStateOf(initialModelPromotionChoice(family, artifact, savedMetadataJson).id)
    }
    val selectedType = typeChoices.firstOrNull { it.id == choiceId } ?: typeChoices.first()
    val inspected = remember(artifact.id) { SdArtifactInspection.fromJson(artifact.validationJson) }
    var sdFamily by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optString("sdFamily").ifBlank { inspected?.detectedFamily?.storedValue.orEmpty() }) }
    var sdVariant by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optString("sdVariant").ifBlank { inspected?.detectedVariant.orEmpty() }) }
    var compatibility by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optString("sdCompatProfiles")) }
    var onnxPipeline by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optString("onnxPipelineFamily")) }
    var liteRtBackend by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optString("liteRtBackend").takeIf { it in setOf("auto", "cpu", "gpu") } ?: "auto") }
    var supportsVision by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optBoolean("supportsVision", false)) }
    var supportsAudio by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optBoolean("supportsAudio", false)) }
    var supportsEmbedding by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optBoolean("supportsEmbedding", false)) }
    var whisperVariant by remember(artifact.id, savedMetadataJson) { mutableStateOf(savedMetadata.optString("whisperVariant")) }
    var stableAudioFamily by remember(artifact.id, savedMetadataJson) {
        mutableStateOf(
            savedMetadata.optString("stableAudioFamily")
                .takeIf {
                    it == StableAudioModelSupport.FAMILY_MUSIC ||
                        it == StableAudioModelSupport.FAMILY_SFX
                }
                ?: StableAudioModelSupport.FAMILY_MUSIC
        )
    }
    var familyMenuExpanded by remember { mutableStateOf(false) }
    var showMismatchWarning by remember(artifact.id) { mutableStateOf(false) }
    val detectedFamily = ModelFamily.fromStoredValue(artifact.detectedFamily)
    val detectedFamilyLabel = if (detectedFamily != null) {
        modelFamilyLabel(detectedFamily)
    } else {
        artifact.detectedFamily
    }
    val detectedTypeLabel = artifact.detectedType
        ?.let { raw -> runCatching { ModelType.valueOf(raw) }.getOrNull()?.name ?: raw }
        ?.replace('_', ' ')
    val classificationWarning = ModelClassificationPolicy.warningFor(
        detectedFamily = artifact.detectedFamily,
        selectedFamily = family.storedValue,
        detectedType = artifact.detectedType,
        selectedType = selectedType.type.name,
        detectedRole = artifact.detectedRole,
        selectedRole = selectedType.role
    )
    val submitPromotion = {
        val stableAudioRole = StableAudioModelSupport.canonicalRole(selectedType.role)
        val metadata = JSONObject(PortableModelMetadata.sanitize(savedMetadataJson)).apply {
            put("modelType", selectedType.type.name)
            put("sdCapabilities", selectedType.sdCapabilities ?: savedMetadata.optString("sdCapabilities").takeIf { it.isNotBlank() })
            put("sdFamily", sdFamily.trim().takeIf { it.isNotBlank() })
            put("sdVariant", sdVariant.trim().takeIf { it.isNotBlank() })
            put("sdCompatProfiles", compatibility.trim().takeIf { it.isNotBlank() })
            put("onnxPipelineFamily", onnxPipeline.trim().takeIf { it.isNotBlank() })
            put("liteRtBackend", liteRtBackend.trim().takeIf { it.isNotBlank() })
            put("supportsVision", supportsVision)
            put("supportsAudio", supportsAudio)
            put("supportsEmbedding", supportsEmbedding)
            put("whisperVariant", whisperVariant.trim().takeIf { it.isNotBlank() })
            if (stableAudioRole != null) {
                put("stableAudioComponentRole", stableAudioRole)
                put(
                    "stableAudioFamily",
                    if (stableAudioRole == StableAudioModelSupport.ROLE_DIT) {
                        stableAudioFamily
                    } else {
                        StableAudioModelSupport.FAMILY_SHARED
                    }
                )
            } else {
                remove("stableAudioComponentRole")
                remove("stableAudioFamily")
            }
        }.toString()
        onPromote(family, name.trim(), selectedType.role, PortableModelMetadata.sanitize(metadata))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_library_promote_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SelectionContainer {
                    Text(artifact.filename, style = MaterialTheme.typography.labelLarge)
                }
                ModelClassificationComparisonCard(
                    detectedFamily = detectedFamilyLabel,
                    detectedType = detectedTypeLabel,
                    detectedRole = artifact.detectedRole,
                    selectedFamily = modelFamilyLabel(family),
                    selectedType = stringResource(selectedType.labelRes),
                    selectedRole = selectedType.role
                )
                Box {
                    OutlinedButton(onClick = { familyMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(modelFamilyLabel(family), modifier = Modifier.weight(1f))
                    }
                    DropdownMenu(expanded = familyMenuExpanded, onDismissRequest = { familyMenuExpanded = false }) {
                        ModelFamily.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(modelFamilyLabel(option)) }, onClick = { family = option; familyMenuExpanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.model_library_promote_name)) },
                    placeholder = { Text(stringResource(R.string.model_library_promote_name_hint)) },
                    singleLine = true
                )
                ModelPromotionDropdown(
                    label = stringResource(R.string.model_library_promote_type),
                    selectedLabel = stringResource(selectedType.labelRes),
                    choices = typeChoices.map { it.id to stringResource(it.labelRes) },
                    onSelect = { choiceId = it }
                )
                OutlinedTextField(
                    value = compatibility,
                    onValueChange = { compatibility = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.model_library_compatibility)) },
                    placeholder = { Text(stringResource(R.string.model_library_compatibility_hint)) },
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.model_library_compatibility_help)) }
                )
                if (family == ModelFamily.SD) {
                    val families = com.example.llamadroid.sd.SdModelFamily.entries.map {
                        it.storedValue to stringResource(com.example.llamadroid.ui.ai.sdFamilyLabelRes(it))
                    }
                    ModelPromotionDropdown(
                        stringResource(R.string.model_library_sd_family),
                        families.firstOrNull { it.first == sdFamily }?.second
                            ?: stringResource(R.string.model_promote_detect_family),
                        families
                    ) { sdFamily = it }
                    OutlinedTextField(
                        value = sdVariant,
                        onValueChange = { sdVariant = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.model_library_sd_variant)) },
                        singleLine = true
                    )
                }
                if (family == ModelFamily.ONNX) {
                    OutlinedTextField(
                        value = onnxPipeline,
                        onValueChange = { onnxPipeline = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.model_library_onnx_pipeline)) },
                        singleLine = true
                    )
                }
                if (family == ModelFamily.LITERT) {
                    val backends = listOf(
                        "auto" to stringResource(R.string.model_promote_auto),
                        "cpu" to stringResource(R.string.model_promote_cpu),
                        "gpu" to stringResource(R.string.litert_backend_gpu)
                    )
                    ModelPromotionDropdown(
                        stringResource(R.string.model_library_litert_backend),
                        backends.first { it.first == liteRtBackend }.second,
                        backends
                    ) { liteRtBackend = it }
                    ModelPromotionToggle(
                        stringResource(R.string.litert_models_modality_vision),
                        supportsVision
                    ) { supportsVision = it }
                    ModelPromotionToggle(
                        stringResource(R.string.litert_models_modality_audio),
                        supportsAudio
                    ) { supportsAudio = it }
                    ModelPromotionToggle(
                        stringResource(R.string.litert_models_modality_embedding),
                        supportsEmbedding
                    ) { supportsEmbedding = it }
                    if (StableAudioModelSupport.canonicalRole(selectedType.role) ==
                        StableAudioModelSupport.ROLE_DIT
                    ) {
                        val stableAudioFamilies = listOf(
                            StableAudioModelSupport.FAMILY_MUSIC to
                                stringResource(R.string.audio_music_music),
                            StableAudioModelSupport.FAMILY_SFX to
                                stringResource(R.string.audio_music_sfx)
                        )
                        ModelPromotionDropdown(
                            label = stringResource(R.string.audio_music_bundle_section),
                            selectedLabel = stableAudioFamilies.first {
                                it.first == stableAudioFamily
                            }.second,
                            choices = stableAudioFamilies,
                            onSelect = { stableAudioFamily = it }
                        )
                    }
                }
                if (family == ModelFamily.WHISPER) {
                    OutlinedTextField(
                        value = whisperVariant,
                        onValueChange = { whisperVariant = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.model_library_whisper_variant)) },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (classificationWarning != null) {
                        showMismatchWarning = true
                    } else {
                        submitPromotion()
                    }
                },
                enabled = !busy && name.isNotBlank()
            ) { Text(stringResource(R.string.model_library_promote_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_library_cancel)) }
        }
    )
    if (showMismatchWarning) {
        ModelClassificationMismatchDialog(
            onDismiss = { showMismatchWarning = false },
            onConfirm = {
                showMismatchWarning = false
                submitPromotion()
            }
        )
    }
}
