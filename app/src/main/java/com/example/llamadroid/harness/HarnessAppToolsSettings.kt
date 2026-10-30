package com.example.llamadroid.harness

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.isInstalledOnnxTxt2ImgBundle
import com.example.llamadroid.sd.SdComponentRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.llamadroid.service.AGENT_SD_IMAGE_SUPPORT_TYPES
import com.example.llamadroid.service.agentSdImageToolParams
import com.example.llamadroid.service.resolveAgentSdImageReadiness

/** Global Harness capability controls; state is the same repository used by the Android bridge. */
@Composable
fun HarnessAppToolsSettingsDialog(runtime: HarnessAppRuntime, onDismiss: () -> Unit) {
    val context = runtime.applicationContext
    val settings = remember(context) { SettingsRepository(context) }
    val models by remember(runtime) {
        runtime.database.modelDao().getModelsByTypes(listOf(
            ModelType.ONNX_IMAGE_GEN,
            ModelType.SD_CHECKPOINT,
            ModelType.SD_DIFFUSION,
        ) + AGENT_SD_IMAGE_SUPPORT_TYPES)
    }.collectAsState(initial = emptyList())
    val imageEnabled by settings.agentImageGenerationToolEnabled.collectAsState()
    val imageEngine by settings.agentImageGenerationEngine.collectAsState()
    val onnxModel by settings.agentImageGenerationModel.collectAsState()
    val sdModel by settings.agentSdImageGenerationModel.collectAsState()
    val sdVae by settings.agentSdImageGenerationVae.collectAsState()
    val sdTae by settings.agentSdImageGenerationTae.collectAsState()
    val sdClipL by settings.agentSdImageGenerationClipL.collectAsState()
    val sdClipG by settings.agentSdImageGenerationClipG.collectAsState()
    val sdT5xxl by settings.agentSdImageGenerationT5xxl.collectAsState()
    val sdLlm by settings.agentSdImageGenerationLlm.collectAsState()
    val sdLlmVision by settings.agentSdImageGenerationLlmVision.collectAsState()
    val sdPhotoMaker by settings.agentSdImageGenerationPhotoMaker.collectAsState()
    val searchEnabled by settings.agentWebSearchEnabled.collectAsState()
    val bgrEnabled by settings.agentBackgroundRemovalToolEnabled.collectAsState()
    val isSdEngine = imageEngine.equals("SD", ignoreCase = true)
    val sdSelections = listOf(sdVae, sdTae, sdClipL, sdClipG, sdT5xxl, sdLlm, sdLlmVision, sdPhotoMaker)
    val sdReadiness = remember(models, sdModel, sdSelections) {
        resolveAgentSdImageReadiness(
            mainModels = models.filter { it.type in setOf(ModelType.SD_CHECKPOINT, ModelType.SD_DIFFUSION) },
            supportModels = models.filter { it.type in AGENT_SD_IMAGE_SUPPORT_TYPES },
            sdParams = settings.agentSdImageToolParams(sdModel),
        )
    }
    val onnxImageReady = onnxModel?.isNotBlank() == true && models.any { model ->
        (model.filename == onnxModel || model.path == onnxModel) && model.isInstalledOnnxTxt2ImgBundle()
    }
    val imageStatusRes = when {
        !imageEnabled -> R.string.harness_app_tool_status_off
        isSdEngine && sdReadiness.ready -> R.string.harness_app_tool_status_ready
        isSdEngine && sdReadiness.modelSelected -> R.string.harness_app_tool_status_needs_components
        isSdEngine -> R.string.harness_app_tool_status_needs_model
        onnxImageReady -> R.string.harness_app_tool_status_ready
        else -> R.string.harness_app_tool_status_needs_model
    }
    val imageModelReady = imageStatusRes == R.string.harness_app_tool_status_ready
    val missingComponentNames = sdReadiness.missingRequiredRoles.map { role ->
        stringResource(when (role) {
            SdComponentRole.VAE -> R.string.imagegen_component_vae
            SdComponentRole.TAE -> R.string.imagegen_component_tae
            SdComponentRole.CLIP_L -> R.string.imagegen_component_clip_l
            SdComponentRole.CLIP_G -> R.string.imagegen_component_clip_g
            SdComponentRole.T5XXL -> R.string.imagegen_component_t5xxl
            SdComponentRole.LLM -> R.string.imagegen_component_llm
            SdComponentRole.LLM_VISION -> R.string.imagegen_component_llm_vision
            SdComponentRole.PHOTOMAKER -> R.string.imagegen_component_photomaker
            else -> R.string.imagegen_component_main_model
        })
    }.joinToString()
    var showImageConfiguration by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.harness_app_tools_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.harness_app_tools_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        ToolToggleRow(
                            title = stringResource(R.string.harness_app_tool_image_title),
                            description = stringResource(R.string.harness_app_tool_image_description),
                            checked = imageEnabled,
                            onCheckedChange = settings::setAgentImageGenerationToolEnabled,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = when {
                                imageStatusRes == R.string.harness_app_tool_status_needs_components &&
                                    sdReadiness.missingRequiredRoles.isNotEmpty() ->
                                    stringResource(R.string.harness_app_tool_status_needs_components, missingComponentNames)
                                imageStatusRes == R.string.harness_app_tool_status_needs_components ->
                                    stringResource(
                                        R.string.harness_app_tool_status_needs_components,
                                        stringResource(R.string.imagegen_component_main_model),
                                    )
                                else -> stringResource(imageStatusRes)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (imageEnabled && imageModelReady) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showImageConfiguration = true }) {
                            Text(stringResource(R.string.harness_app_tool_configure_image))
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        ToolToggleRow(
                            title = stringResource(R.string.agent_bgr_tool_name),
                            description = stringResource(R.string.agent_bgr_tool_enabled_desc),
                            checked = bgrEnabled,
                            onCheckedChange = settings::setAgentBackgroundRemovalToolEnabled,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(if (bgrEnabled) R.string.harness_app_tool_status_enabled else R.string.harness_app_tool_status_off),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (bgrEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        ToolToggleRow(
                            title = stringResource(R.string.harness_app_tool_web_search_title),
                            description = stringResource(R.string.harness_app_tool_web_search_description),
                            checked = searchEnabled,
                            onCheckedChange = settings::setAgentWebSearchEnabled,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(if (searchEnabled) R.string.harness_app_tool_status_enabled else R.string.harness_app_tool_status_off),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (searchEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HarnessKvCacheCard(runtime.runtimeId)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.harness_runtime_close)) } },
    )

    if (showImageConfiguration) {
        HarnessSharedToolSettings(settings, runtime) { showImageConfiguration = false }
    }
}

@Composable
private fun ToolToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private const val GIB = 1024L * 1024 * 1024
private val KV_BUDGET_OPTIONS_GIB = listOf(1, 2, 4, 8, 16)

/** Canonical home of the Harness KV-cache-on-disk controls (the chat delegation section mirrors it). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HarnessKvCacheCard(runtimeId: String) {
    val context = LocalContext.current
    val kvSettings = remember(context) { HarnessKvCacheSettings.get(context) }
    val enabled by kvSettings.enabled.collectAsState()
    val budget by kvSettings.budgetBytes.collectAsState()
    var sizeTick by remember { mutableIntStateOf(0) }
    val sizeBytes by produceState(0L, runtimeId, sizeTick) {
        value = withContext(Dispatchers.IO) { runCatching { kvSettings.sizeForRuntime(runtimeId) }.getOrDefault(0L) }
    }
    var confirmClear by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolToggleRow(
                title = stringResource(R.string.harness_kv_cache_title),
                description = stringResource(R.string.harness_kv_cache_description),
                checked = enabled,
                onCheckedChange = kvSettings::setEnabled,
            )
            Text(
                text = stringResource(R.string.harness_kv_cache_size, Formatter.formatShortFileSize(context, sizeBytes)),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.harness_kv_cache_budget),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KV_BUDGET_OPTIONS_GIB.forEach { gib ->
                    FilterChip(
                        selected = budget == gib * GIB,
                        onClick = { kvSettings.setBudgetBytes(gib * GIB) },
                        enabled = enabled,
                        label = { Text(stringResource(R.string.harness_kv_cache_budget_option, gib), maxLines = 1) },
                    )
                }
            }
            OutlinedButton(onClick = { confirmClear = true }, enabled = sizeBytes > 0) {
                Text(stringResource(R.string.harness_kv_cache_clear), maxLines = 2)
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.harness_kv_cache_clear_title)) },
            text = {
                Text(
                    stringResource(R.string.harness_kv_cache_clear_body),
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    runCatching { kvSettings.clearRuntime(runtimeId) }
                    sizeTick++
                    Toast.makeText(context, R.string.harness_kv_cache_cleared, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.harness_kv_cache_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
