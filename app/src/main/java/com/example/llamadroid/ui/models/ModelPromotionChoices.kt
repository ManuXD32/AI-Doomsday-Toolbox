package com.example.llamadroid.ui.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.PendingModelArtifactEntity
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.data.model.PortableModelMetadata
import com.example.llamadroid.ui.ai.SDModelSelectionType

internal data class ModelPromotionChoice(val id: String, val role: String, val type: ModelType,
    val labelRes: Int, val sdCapabilities: String? = null)

internal fun initialModelPromotionChoice(family: ModelFamily, artifact: PendingModelArtifactEntity,
    savedMetadataJson: String?): ModelPromotionChoice {
    val choices = modelPromotionChoices(family)
    val saved = org.json.JSONObject(PortableModelMetadata.sanitize(savedMetadataJson))
    val role = artifact.requestedRole ?: artifact.detectedRole
    val type = saved.optString("modelType").ifBlank { artifact.detectedType.orEmpty() }
    val capabilities = saved.optString("sdCapabilities")
    return choices.firstOrNull { it.type.name == type && it.sdCapabilities != null && it.sdCapabilities == capabilities }
        ?: choices.firstOrNull { it.role == role }
        ?: choices.firstOrNull { it.type.name == type }
        ?: choices.first()
}

internal fun modelPromotionChoices(family: ModelFamily): List<ModelPromotionChoice> = when (family) {
    ModelFamily.SD -> SDModelSelectionType.entries.map { entry ->
        val role = when (entry.storedType) {
            ModelType.LLM -> "llm"
            ModelType.VISION_PROJECTOR -> "llm_vision"
            else -> entry.storedType.name.removePrefix("SD_").lowercase(java.util.Locale.ROOT)
        }
        ModelPromotionChoice(entry.name, role, entry.storedType, entry.labelRes,
            "vid_gen".takeIf { entry == SDModelSelectionType.VIDEO_GEN })
    }
    ModelFamily.LLM -> listOf(
        ModelPromotionChoice("llm", "llm", ModelType.LLM, R.string.models_type_llm),
        ModelPromotionChoice("draft", "draft", ModelType.LLM_DRAFT, R.string.models_type_mtp),
        ModelPromotionChoice("lora", "lora", ModelType.LORA, R.string.models_type_lora),
        ModelPromotionChoice("embedding", "embedding", ModelType.EMBEDDING, R.string.models_type_embedding),
        ModelPromotionChoice("projector", "vision_projector", ModelType.VISION_PROJECTOR, R.string.models_type_vision_projector)
    )
    ModelFamily.ONNX -> listOf(
        ModelPromotionChoice("image", "image_gen", ModelType.ONNX_IMAGE_GEN, R.string.model_promote_onnx_image),
        ModelPromotionChoice("background", "background_removal", ModelType.ONNX_BACKGROUND_REMOVAL, R.string.model_promote_onnx_background),
        ModelPromotionChoice("upscaler", "upscaler", ModelType.ONNX_IMAGE_UPSCALER, R.string.model_promote_onnx_upscaler),
        ModelPromotionChoice("tts", "tts", ModelType.ONNX_TTS, R.string.model_promote_onnx_tts)
    )
    ModelFamily.LITERT -> listOf(ModelPromotionChoice("litert", "litert_model", ModelType.LLM, R.string.model_library_family_litert))
    ModelFamily.WHISPER -> listOf(ModelPromotionChoice("whisper", "whisper_model", ModelType.WHISPER, R.string.model_library_family_whisper))
}

@Composable
internal fun ModelPromotionDropdown(label: String, selectedLabel: String,
    choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(selectedLabel)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp)) {
                choices.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) },
                    onClick = { onSelect(id); expanded = false }) }
            }
        }
    }
}

@Composable
internal fun ModelPromotionToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}
