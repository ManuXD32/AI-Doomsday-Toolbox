package com.example.llamadroid.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.binary.EffectiveLlamaBinary
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SavedCommand
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.data.db.launchProfile
import com.example.llamadroid.data.db.savedCommandFromLaunchProfile
import com.example.llamadroid.service.LlamaSpeculativeMode
import com.example.llamadroid.service.LlamaLoadMode
import com.example.llamadroid.service.LlamaLoraSpec
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.effectiveSpeculativeDraftPath
import com.example.llamadroid.service.speculativeDraftModelsFor
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import kotlinx.coroutines.launch
import java.util.Locale

private typealias SavedCommandEntity = SavedCommand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmModeDropdown(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.heightIn(max = 420.dp)
        ) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        onSelected(value)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun LlmAdvancedToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

internal data class LlamaParallelContextBreakdown(
    val totalContext: Int,
    val parallel: Int,
    val contextPerSequence: Int,
    val remainder: Int
)

internal fun calculateLlamaParallelContext(
    totalContext: Int,
    configuredParallel: Int?
): LlamaParallelContextBreakdown {
    val safeContext = totalContext.coerceAtLeast(0)
    val effectiveParallel = (configuredParallel ?: 1).coerceAtLeast(1)
    return LlamaParallelContextBreakdown(
        totalContext = safeContext,
        parallel = effectiveParallel,
        contextPerSequence = safeContext / effectiveParallel,
        remainder = safeContext % effectiveParallel
    )
}

internal fun containsManagedLlamaFlag(customFlags: String): Boolean {
    val managed = setOf(
        "--parallel", "-np", "--cache-ram", "-cram", "--ctx-checkpoints", "-ctxcp",
        "--swa-checkpoints", "--checkpoint-min-step", "-cms", "--cache-prompt",
        "--no-cache-prompt", "--cache-idle-slots", "--no-cache-idle-slots",
        "--kv-unified", "-kvu", "--no-kv-unified", "-no-kvu", "--swa-full",
        "--no-swa-full", "--sleep-idle-seconds"
    )
    return customFlags
        .split(Regex("\\s+"))
        .asSequence()
        .map { it.substringBefore('=') }
        .any { it in managed }
}

@Composable
private fun DraftIntTextField(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    valueRange: IntRange? = null,
    singleLine: Boolean = true
) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it.filter(Char::isDigit)
            draft.toIntOrNull()?.let { parsed ->
                if (valueRange?.contains(parsed) != false) onValueChange(parsed)
            }
        },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine
    )
}

@Composable
private fun DraftNullableIntTextField(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    valueRange: IntRange? = null
) {
    var draft by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it.filter(Char::isDigit)
            if (draft.isBlank()) {
                onValueChange(null)
            } else {
                draft.toIntOrNull()?.let { parsed ->
                    if (valueRange?.contains(parsed) != false) onValueChange(parsed)
                }
            }
        },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine
    )
}

@Composable
private fun DraftFloatTextField(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float>? = null,
    allowSigned: Boolean = false
) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = buildString {
                var seenDot = false
                it.forEachIndexed { index, ch ->
                    if (ch.isDigit()) append(ch)
                    if (ch == '-' && allowSigned && index == 0) append(ch)
                    if (ch == '.' && !seenDot) {
                        append(ch)
                        seenDot = true
                    }
                }
            }
            draft.toFloatOrNull()
                ?.takeIf { it.isFinite() }
                ?.let { parsed ->
                    if (valueRange?.contains(parsed) != false) onValueChange(parsed)
                }
        },
        modifier = modifier,
        label = label,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

private fun loraPathsMatch(first: String, second: String): Boolean =
    first.trim().equals(second.trim(), ignoreCase = true)

private fun safeLoraStrength(value: Float): Float =
    if (value.isFinite()) value else 1f

private fun LlamaLoadMode.localizedLabelRes(): Int = when (name.lowercase(Locale.ROOT)) {
    "auto" -> R.string.llm_load_mode_auto
    "none" -> R.string.llm_load_mode_none
    "mmap" -> R.string.llm_load_mode_mmap
    "mlock" -> R.string.llm_load_mode_mlock
    "mmap_mlock", "mmap+mlock" -> R.string.llm_load_mode_mmap_mlock
    "dio", "direct_io", "directio" -> R.string.llm_load_mode_dio
    else -> R.string.llm_load_mode_unknown
}

private fun LlamaLoadMode.localizedDescriptionRes(): Int = when (name.lowercase(Locale.ROOT)) {
    "auto" -> R.string.llm_load_mode_auto_desc
    "none" -> R.string.llm_load_mode_none_desc
    "mmap" -> R.string.llm_load_mode_mmap_desc
    "mlock" -> R.string.llm_load_mode_mlock_desc
    "mmap_mlock", "mmap+mlock" -> R.string.llm_load_mode_mmap_mlock_desc
    "dio", "direct_io", "directio" -> R.string.llm_load_mode_dio_desc
    else -> R.string.llm_load_mode_unknown_desc
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlamaLoadModePicker(
    selected: LlamaLoadMode,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (LlamaLoadMode) -> Unit
) {
    val modes = remember { LlamaLoadMode.entries.toList() }
    val selectedLabel = stringResource(selected.localizedLabelRes())
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.llm_load_mode_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(mode.localizedLabelRes()),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                stringResource(mode.localizedDescriptionRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    onClick = {
                        onSelected(mode)
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        stringResource(selected.localizedDescriptionRes()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LlamaLoraStackRow(
    index: Int,
    spec: LlamaLoraSpec,
    onStrengthChange: (Float) -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: () -> Unit
) {
    val strength = safeLoraStrength(spec.strength)
    val sliderStrength = strength.coerceIn(0f, 2f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    stringResource(R.string.llm_lora_stack_item, index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    spec.path.substringAfterLast('/').ifBlank { spec.path },
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.llm_lora_stack_strength),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.widthIn(max = 78.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Slider(
                        value = sliderStrength,
                        onValueChange = { onStrengthChange(it.coerceIn(0f, 2f)) },
                        valueRange = 0f..2f,
                        steps = 19,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        String.format(Locale.ROOT, "%.3g", strength.toDouble()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(min = 44.dp, max = 72.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DraftFloatTextField(
                    value = strength,
                    onValueChange = { onStrengthChange(safeLoraStrength(it)) },
                    label = { Text(stringResource(R.string.llm_lora_stack_strength_input)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    allowSigned = true
                )
                Text(
                    stringResource(R.string.llm_lora_stack_strength_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    ',' in spec.path -> R.string.llm_lora_stack_invalid_comma
                    strength != 1f && ':' in spec.path -> R.string.llm_lora_stack_invalid_scaled_colon
                    else -> null
                }?.let { errorRes ->
                    Text(
                        stringResource(errorRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.width(48.dp)
            ) {
                IconButton(
                    onClick = { onMoveUp?.invoke() },
                    enabled = onMoveUp != null,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.llm_lora_stack_move_up),
                    )
                }
                IconButton(
                    onClick = { onMoveDown?.invoke() },
                    enabled = onMoveDown != null,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.llm_lora_stack_move_down),
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.llm_lora_stack_remove),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * LLM/Chat Settings - Threads, Context Size, Temperature, Vision
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LLMSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val settingsRepo = remember { SettingsRepository(context) }
    val binaryRepo = remember { BinaryRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    
    val threads by settingsRepo.threads.collectAsState()
    val ctxSize by settingsRepo.contextSize.collectAsState()
    val temp by settingsRepo.temperature.collectAsState()
    val selectedModelPath by settingsRepo.selectedModelPath.collectAsState()
    val selectedLlmLoras by settingsRepo.selectedLlmLoras.collectAsState()
    val selectedLlmLoraPath by settingsRepo.selectedLlmLoraPath.collectAsState()
    val llamaLoadMode by settingsRepo.llamaLoadMode.collectAsState()
    val enableVision by settingsRepo.enableVision.collectAsState()
    val llmNativeBinarySelection by settingsRepo.llmNativeBinarySelection.collectAsState()
    val llamaOpenClCpuTargetGpuDraft by settingsRepo.llamaOpenClCpuTargetGpuDraft.collectAsState()
    val effectiveLlmBinary = remember(llmNativeBinarySelection) {
        runCatching {
            binaryRepo.resolveCurrentLlamaBinary(llmNativeBinarySelection).effective
        }.getOrNull()
    }
    val isUsingOpenClBinary = effectiveLlmBinary == EffectiveLlamaBinary.OPENCL
    
    val speculativeEnabled by settingsRepo.speculativeEnabled.collectAsState()
    val speculativeMode by settingsRepo.speculativeMode.collectAsState()
    val draftModelPath by settingsRepo.draftModelPath.collectAsState()
    val draftMaxTokens by settingsRepo.draftMaxTokens.collectAsState()
    val draftMinTokens by settingsRepo.draftMinTokens.collectAsState()
    val draftPMin by settingsRepo.draftPMin.collectAsState()
    val draftThreads by settingsRepo.draftThreads.collectAsState()
    val draftThreadsBatch by settingsRepo.draftThreadsBatch.collectAsState()
    val mtpDraftMaxTokens by settingsRepo.mtpDraftMaxTokens.collectAsState()
    val mtpDraftMinTokens by settingsRepo.mtpDraftMinTokens.collectAsState()
    val mtpDraftPMin by settingsRepo.mtpDraftPMin.collectAsState()
    val mtpUseDraftModel by settingsRepo.mtpUseDraftModel.collectAsState()
    val ngramModNMatch by settingsRepo.ngramModNMatch.collectAsState()
    val ngramModNMin by settingsRepo.ngramModNMin.collectAsState()
    val ngramModNMax by settingsRepo.ngramModNMax.collectAsState()
    val ngramSimpleSizeN by settingsRepo.ngramSimpleSizeN.collectAsState()
    val ngramSimpleSizeM by settingsRepo.ngramSimpleSizeM.collectAsState()
    val ngramSimpleMinHits by settingsRepo.ngramSimpleMinHits.collectAsState()
    val ngramMapKSizeN by settingsRepo.ngramMapKSizeN.collectAsState()
    val ngramMapKSizeM by settingsRepo.ngramMapKSizeM.collectAsState()
    val ngramMapKMinHits by settingsRepo.ngramMapKMinHits.collectAsState()
    val ngramMapK4VSizeN by settingsRepo.ngramMapK4VSizeN.collectAsState()
    val ngramMapK4VSizeM by settingsRepo.ngramMapK4VSizeM.collectAsState()
    val ngramMapK4VMinHits by settingsRepo.ngramMapK4VMinHits.collectAsState()
    val llamaNativeToolsEnabled by settingsRepo.llamaNativeToolsEnabled.collectAsState()
    val flashAttentionEnabled by settingsRepo.flashAttentionEnabled.collectAsState()
    val serverPort by settingsRepo.serverPort.collectAsState()
    val serverBatchSize by settingsRepo.serverBatchSize.collectAsState()
    val serverPhysicalBatchSize by settingsRepo.serverPhysicalBatchSize.collectAsState()
    val serverThreadsBatch by settingsRepo.serverThreadsBatch.collectAsState()
    val serverParallel by settingsRepo.serverParallel.collectAsState()
    val parallelContextBreakdown = remember(ctxSize, serverParallel) {
        calculateLlamaParallelContext(ctxSize, serverParallel)
    }
    val serverCacheRam by settingsRepo.serverCacheRam.collectAsState()
    val serverContextCheckpoints by settingsRepo.serverContextCheckpoints.collectAsState()
    val serverCheckpointMinStep by settingsRepo.serverCheckpointMinStep.collectAsState()
    val serverCachePrompt by settingsRepo.serverCachePrompt.collectAsState()
    val serverCacheIdleSlots by settingsRepo.serverCacheIdleSlots.collectAsState()
    val serverKvUnifiedMode by settingsRepo.serverKvUnifiedMode.collectAsState()
    val serverSwaFull by settingsRepo.serverSwaFull.collectAsState()
    val serverSleepIdleSeconds by settingsRepo.serverSleepIdleSeconds.collectAsState()
    val agentLlamaSlotAffinityMode by settingsRepo.agentLlamaSlotAffinityMode.collectAsState()
    val agentPromptCacheDiagnostics by settingsRepo.agentPromptCacheDiagnostics.collectAsState()
    val agentDeveloperPromptComparison by settingsRepo.agentDeveloperPromptComparison.collectAsState()
    var promptCacheAdvancedExpanded by rememberSaveable { mutableStateOf(false) }
    var kvUnifiedMenuExpanded by remember { mutableStateOf(false) }
    var slotAffinityMenuExpanded by remember { mutableStateOf(false) }
    
    // Custom Commands Additions
    val customFlags by settingsRepo.customFlags.collectAsState()
    var customFlagsText by remember(customFlags) { mutableStateOf(customFlags) }
    val customCommandTemplate by settingsRepo.customCommandTemplate.collectAsState()
    var customCommandTemplateText by remember(customCommandTemplate) { mutableStateOf(customCommandTemplate) }
    val kvCacheEnabled by settingsRepo.serverKvCacheEnabled.collectAsState()
    val kvCacheTypeK by settingsRepo.serverKvCacheTypeK.collectAsState()
    val kvCacheTypeV by settingsRepo.serverKvCacheTypeV.collectAsState()
    val kvCacheReuse by settingsRepo.serverKvCacheReuse.collectAsState()
    val llamaKvOffloadMode by settingsRepo.llamaKvOffloadMode.collectAsState()
    val llamaDraftDeviceMode by settingsRepo.llamaDraftDeviceMode.collectAsState()
    
    var showSaveCommandDialog by remember { mutableStateOf(false) }
    var speculativeModeMenuExpanded by remember { mutableStateOf(false) }
    var saveCommandName by remember { mutableStateOf("") }
    var selectedSaveCommandId by remember { mutableStateOf<Long?>(null) }
    var showOverwriteSavedCommandDialog by remember { mutableStateOf(false) }
    var showLoadCommandDialog by remember { mutableStateOf(false) }
    var showCommandPreview by remember { mutableStateOf<SavedCommandEntity?>(null) }
    
    val savedCommands by db.savedCommandDao()
        .getCommandsByScope(SavedCommandScopes.GENERAL)
        .collectAsState(initial = emptyList())
    val speculativeRuns by db.llamaSpeculativeRunDao().observeRuns().collectAsState(initial = emptyList())
    // val scope = rememberCoroutineScope() // Duplicate declaration, removed

    val llmModels by db.modelDao()
        .getModelsByTypes(listOf(ModelType.LLM, ModelType.VISION))
        .collectAsState(initial = emptyList())
    val mtpModels by db.modelDao().getModelsByType(ModelType.LLM_DRAFT).collectAsState(initial = emptyList())
    val loraAdapters by db.modelDao().getModelsByType(ModelType.LORA).collectAsState(initial = emptyList())
    val visionProjectorModels by db.modelDao().getModelsByType(ModelType.VISION_PROJECTOR).collectAsState(initial = emptyList())

    val draftSelectorModels = remember(llmModels, mtpModels, speculativeMode) {
        speculativeDraftModelsFor(llmModels + mtpModels, speculativeMode)
    }
    val draftSelectorTitleRes = if (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP) {
        R.string.models_type_mtp
    } else {
        R.string.dist_speculative_draft_model
    }
    val effectiveDraftModelPath = remember(draftModelPath, draftSelectorModels) {
        effectiveSpeculativeDraftPath(draftModelPath, draftSelectorModels)
    }

    // Keep a non-empty persisted path from surviving a mode-family/model-list
    // change, but do not clear it while the database-backed candidate list is
    // still empty during initial loading. The effective value is used below in
    // the meantime, so launch validation cannot accept an incompatible path.
    LaunchedEffect(draftModelPath, draftSelectorModels) {
        if (draftSelectorModels.isNotEmpty() && draftModelPath != effectiveDraftModelPath) {
            settingsRepo.setDraftModelPath(effectiveDraftModelPath)
        }
    }

    val selectedModel = llmModels.find { it.path == selectedModelPath }
    // Legacy VISION rows are image-capable LLMs even when their old isVision flag is unset.
    val hasVisionCapability = selectedModel?.let { it.isVision || it.type == ModelType.VISION } == true &&
        visionProjectorModels.isNotEmpty()
    
    val selectedMmprojPath by settingsRepo.selectedMmprojPath.collectAsState()

    // Only disable vision when models are loaded AND no vision capability
    // This prevents race condition where llmModels is initially empty
    LaunchedEffect(hasVisionCapability, llmModels) {
        if (llmModels.isNotEmpty() && !hasVisionCapability && enableVision) {
            settingsRepo.setEnableVision(false)
        }
    }
    
    var showLlmSelector by remember { mutableStateOf(false) }
    var showLoraSelector by remember { mutableStateOf(false) }
    var loraDialogDraft by remember { mutableStateOf<List<LlamaLoraSpec>>(emptyList()) }
    var loadModeMenuExpanded by remember { mutableStateOf(false) }
    var showDraftSelector by remember { mutableStateOf(false) }

    // The legacy single-path preference remains a fallback while an older
    // install or a saved command is being upgraded to the ordered list.
    val selectedLoraStack = remember(selectedLlmLoras, selectedLlmLoraPath) {
        selectedLlmLoras.ifEmpty {
            selectedLlmLoraPath
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(LlamaLoraSpec(path = it, strength = 1f)) }
                .orEmpty()
        }
    }
    fun persistLoraStack(value: List<LlamaLoraSpec>) {
        settingsRepo.setSelectedLlmLoras(value)
    }
    
    AppScreenScaffold(
        title = stringResource(R.string.llm_settings_title),
        subtitle = stringResource(R.string.settings_llm_desc),
        onBack = { navController.popBackStack() }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(
                start = AppChromeDefaults.ScreenPadding,
                top = 12.dp,
                end = AppChromeDefaults.ScreenPadding,
                bottom = AppChromeDefaults.ScreenPadding
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Active Model
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "⭐ " + stringResource(R.string.llm_active_model),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showLlmSelector = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                selectedModelPath?.substringAfterLast("/") ?: stringResource(R.string.llm_no_model),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.llm_lora_stack_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.llm_lora_stack_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        if (selectedLoraStack.isEmpty()) {
                            Text(
                                stringResource(R.string.llm_lora_stack_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                selectedLoraStack.forEachIndexed { index, spec ->
                                    LlamaLoraStackRow(
                                        index = index,
                                        spec = spec,
                                        onStrengthChange = { strength ->
                                            val updated = selectedLoraStack.toMutableList()
                                            updated[index] = spec.copy(strength = safeLoraStrength(strength))
                                            persistLoraStack(updated)
                                        },
                                        onMoveUp = if (index > 0) {
                                            {
                                                val updated = selectedLoraStack.toMutableList()
                                                updated.add(index - 1, updated.removeAt(index))
                                                persistLoraStack(updated)
                                            }
                                        } else null,
                                        onMoveDown = if (index < selectedLoraStack.lastIndex) {
                                            {
                                                val updated = selectedLoraStack.toMutableList()
                                                updated.add(index + 1, updated.removeAt(index))
                                                persistLoraStack(updated)
                                            }
                                        } else null,
                                        onRemove = {
                                            persistLoraStack(selectedLoraStack.toMutableList().also { it.removeAt(index) })
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.llm_lora_stack_ram_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    loraDialogDraft = selectedLoraStack
                                    showLoraSelector = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.llm_lora_stack_add),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (selectedLoraStack.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { persistLoraStack(emptyList()) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        stringResource(R.string.llm_lora_stack_clear),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // Custom Commands Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "💾 " + stringResource(R.string.dist_load_command_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showLoadCommandDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Menu, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.dist_load_command),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            
                            Button(
                                onClick = {
                                    selectedSaveCommandId = null
                                    saveCommandName = ""
                                    showSaveCommandDialog = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Star, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.dist_save_command),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            
            // Custom Flags
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.command_template_title),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.command_template_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customCommandTemplateText,
                            onValueChange = {
                                customCommandTemplateText = it
                                settingsRepo.setCustomCommandTemplate(it)
                            },
                            label = { Text(stringResource(R.string.command_template_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            placeholder = { Text(stringResource(R.string.command_template_placeholder)) },
                            supportingText = {
                                Text(stringResource(R.string.command_template_placeholders))
                            }
                        )
                        if (
                            selectedLoraStack.size > 1 &&
                            customCommandTemplateText
                                .replace("--lora {lora}", "")
                                .contains("{lora}")
                        ) {
                            Text(
                                stringResource(R.string.command_template_legacy_lora_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.dist_advanced_custom_flags), fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                            value = customFlagsText,
                            onValueChange = { 
                                customFlagsText = it 
                                settingsRepo.setCustomFlags(it)
                            },
                            label = { Text(stringResource(R.string.dist_advanced_custom_flags)) },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 3
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.llm_native_tools_title),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        stringResource(R.string.llm_native_tools_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = llamaNativeToolsEnabled,
                                    onCheckedChange = settingsRepo::setLlamaNativeToolsEnabled
                                )
                            }
                        }
                    }
                }
            
            // Generated llama.cpp parameters
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.llm_generated_params_title),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.llm_generated_params_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DraftIntTextField(
                            value = serverPort,
                            onValueChange = settingsRepo::setServerPort,
                            valueRange = 1..65535,
                            label = { Text(stringResource(R.string.llm_port)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        DraftIntTextField(
                            value = serverBatchSize,
                            onValueChange = settingsRepo::setServerBatchSize,
                            valueRange = 1..131072,
                            label = { Text(stringResource(R.string.dist_batch_size)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        DraftNullableIntTextField(
                            value = serverPhysicalBatchSize,
                            onValueChange = settingsRepo::setServerPhysicalBatchSize,
                            valueRange = 1..131072,
                            label = { Text(stringResource(R.string.llm_physical_batch_size)) },
                            placeholder = { Text(stringResource(R.string.llm_optional_flag_blank)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        DraftNullableIntTextField(
                            value = serverThreadsBatch,
                            onValueChange = settingsRepo::setServerThreadsBatch,
                            valueRange = 1..131072,
                            label = { Text(stringResource(R.string.llm_threads_batch)) },
                            placeholder = { Text(stringResource(R.string.llm_optional_flag_blank)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { promptCacheAdvancedExpanded = !promptCacheAdvancedExpanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.llm_prompt_cache_advanced_title),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    stringResource(R.string.llm_prompt_cache_advanced_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                if (promptCacheAdvancedExpanded) Icons.Default.KeyboardArrowUp
                                else Icons.Default.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                        AnimatedVisibility(promptCacheAdvancedExpanded) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                DraftNullableIntTextField(
                                    value = serverParallel,
                                    onValueChange = settingsRepo::setServerParallel,
                                    valueRange = 1..512,
                                    label = { Text(stringResource(R.string.dist_advanced_parallel)) },
                                    placeholder = { Text(stringResource(R.string.llm_optional_flag_blank)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Text(
                                    stringResource(R.string.llm_parallel_slot_explanation),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Max context per sequence: " +
                                        "${parallelContextBreakdown.contextPerSequence} tokens",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = buildString {
                                        append(parallelContextBreakdown.totalContext)
                                        append(" total ÷ ")
                                        append(parallelContextBreakdown.parallel)
                                        append(
                                            if (parallelContextBreakdown.parallel == 1) {
                                                " sequence"
                                            } else {
                                                " sequences"
                                            }
                                        )
                                        if (parallelContextBreakdown.remainder > 0) {
                                            append(" · remainder ")
                                            append(parallelContextBreakdown.remainder)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                DraftNullableIntTextField(
                                    value = serverCacheRam,
                                    onValueChange = settingsRepo::setServerCacheRam,
                                    valueRange = 0..262144,
                                    label = { Text(stringResource(R.string.dist_advanced_cache_ram)) },
                                    placeholder = { Text(stringResource(R.string.llm_optional_flag_blank)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                DraftNullableIntTextField(
                                    value = serverContextCheckpoints,
                                    onValueChange = settingsRepo::setServerContextCheckpoints,
                                    valueRange = 0..4096,
                                    label = { Text(stringResource(R.string.llm_context_checkpoints)) },
                                    placeholder = { Text(stringResource(R.string.llm_optional_flag_blank)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                DraftNullableIntTextField(
                                    value = serverCheckpointMinStep,
                                    onValueChange = settingsRepo::setServerCheckpointMinStep,
                                    valueRange = 0..1_048_576,
                                    label = { Text(stringResource(R.string.llm_checkpoint_min_step)) },
                                    placeholder = { Text(stringResource(R.string.llm_optional_flag_blank)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                LlmAdvancedToggle(
                                    title = stringResource(R.string.llm_cache_prompt),
                                    description = stringResource(R.string.llm_cache_prompt_desc),
                                    checked = serverCachePrompt,
                                    onCheckedChange = settingsRepo::setServerCachePrompt
                                )
                                LlmAdvancedToggle(
                                    title = stringResource(R.string.llm_cache_idle_slots),
                                    description = stringResource(R.string.llm_cache_idle_slots_desc),
                                    checked = serverCacheIdleSlots,
                                    onCheckedChange = settingsRepo::setServerCacheIdleSlots
                                )
                                LlmModeDropdown(
                                    label = stringResource(R.string.llm_kv_unified),
                                    selected = serverKvUnifiedMode,
                                    options = listOf(
                                        "auto" to stringResource(R.string.general_acceleration_mode_auto),
                                        "enabled" to stringResource(R.string.action_enable),
                                        "disabled" to stringResource(R.string.action_disable)
                                    ),
                                    expanded = kvUnifiedMenuExpanded,
                                    onExpandedChange = { kvUnifiedMenuExpanded = it },
                                    onSelected = settingsRepo::setServerKvUnifiedMode
                                )
                                LlmAdvancedToggle(
                                    title = stringResource(R.string.llm_swa_full),
                                    description = stringResource(R.string.llm_swa_full_warning),
                                    checked = serverSwaFull,
                                    onCheckedChange = settingsRepo::setServerSwaFull
                                )
                                DraftIntTextField(
                                    value = serverSleepIdleSeconds,
                                    onValueChange = { settingsRepo.setServerSleepIdleSeconds(it) },
                                    valueRange = 0..604_800,
                                    label = { Text(stringResource(R.string.llm_sleep_idle_seconds)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Text(
                                    stringResource(R.string.llm_sleep_idle_seconds_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                LlmModeDropdown(
                                    label = stringResource(R.string.llm_slot_affinity),
                                    selected = agentLlamaSlotAffinityMode,
                                    options = listOf(
                                        "automatic" to stringResource(R.string.general_acceleration_mode_auto),
                                        "enabled" to stringResource(R.string.action_enable),
                                        "disabled" to stringResource(R.string.action_disable)
                                    ),
                                    expanded = slotAffinityMenuExpanded,
                                    onExpandedChange = { slotAffinityMenuExpanded = it },
                                    onSelected = settingsRepo::setAgentLlamaSlotAffinityMode
                                )
                                LlmAdvancedToggle(
                                    title = stringResource(R.string.llm_prompt_cache_diagnostics),
                                    description = stringResource(R.string.llm_prompt_cache_diagnostics_desc),
                                    checked = agentPromptCacheDiagnostics,
                                    onCheckedChange = settingsRepo::setAgentPromptCacheDiagnostics
                                )
                                LlmAdvancedToggle(
                                    title = stringResource(R.string.llm_developer_prompt_comparison),
                                    description = stringResource(R.string.llm_developer_prompt_comparison_warning),
                                    checked = agentDeveloperPromptComparison,
                                    onCheckedChange = settingsRepo::setAgentDeveloperPromptComparison
                                )
                                if (isUsingOpenClBinary) {
                                    HorizontalDivider()
                                    LlmAdvancedToggle(
                                        title = stringResource(R.string.llm_opencl_cpu_target_gpu_draft_title),
                                        description = stringResource(R.string.llm_opencl_cpu_target_gpu_draft_desc),
                                        checked = llamaOpenClCpuTargetGpuDraft,
                                        onCheckedChange = settingsRepo::setLlamaOpenClCpuTargetGpuDraft
                                    )
                                }
                                if (containsManagedLlamaFlag(customFlagsText)) {
                                    Text(
                                        stringResource(R.string.llm_managed_flag_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Threads
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.llm_threads), fontWeight = FontWeight.Medium)
                            Text("$threads", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = threads.toFloat(),
                            onValueChange = { settingsRepo.setThreads(it.toInt()) },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                    }
                }
            }
            
            // Context Size
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.llm_context_size), fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        DraftIntTextField(
                            value = ctxSize,
                            onValueChange = settingsRepo::setContextSize,
                            valueRange = 128..131072,
                            label = { Text(stringResource(R.string.llm_context_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
            
            // Temperature
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.llm_temperature), fontWeight = FontWeight.Medium)
                            Text(
                                String.format(java.util.Locale.getDefault(), "%.1f", temp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = temp,
                            onValueChange = { settingsRepo.setTemperature(it) },
                            valueRange = 0f..2f,
                            steps = 19
                        )
                    }
                }
            }
            
            // Remote Access
            item {
                val remoteAccess by settingsRepo.remoteAccess.collectAsState()
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("📡 " + stringResource(R.string.llm_remote_access), fontWeight = FontWeight.Bold)
                                Text(
                                    if (remoteAccess) stringResource(R.string.remote_access_enabled) else stringResource(R.string.remote_access_disabled),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = remoteAccess,
                                onCheckedChange = { settingsRepo.setRemoteAccess(it) }
                            )
                        }
                    }
                }
            }
            
            // KV Cache Optimization
            item {
                val cacheTypes = listOf("f16", "q8_0", "q4_0")
                var showTypeKMenu by remember { mutableStateOf(false) }
                var showTypeVMenu by remember { mutableStateOf(false) }
                var showKvOffloadMenu by remember { mutableStateOf(false) }
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("💾 " + stringResource(R.string.kv_cache_title), fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.kv_cache_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = kvCacheEnabled,
                                onCheckedChange = { settingsRepo.setServerKvCacheEnabled(it) }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        LlmModeDropdown(
                            label = stringResource(R.string.llm_kv_offload_mode),
                            selected = llamaKvOffloadMode,
                            options = listOf(
                                SettingsRepository.LLAMA_KV_OFFLOAD_AUTO to stringResource(R.string.llm_backend_mode_auto),
                                SettingsRepository.LLAMA_KV_OFFLOAD_ACCELERATOR to stringResource(R.string.llm_kv_offload_accelerator),
                                SettingsRepository.LLAMA_KV_OFFLOAD_CPU to stringResource(R.string.llm_kv_offload_cpu)
                            ),
                            expanded = showKvOffloadMenu,
                            onExpandedChange = { showKvOffloadMenu = it },
                            onSelected = settingsRepo::setLlamaKvOffloadMode
                        )
                        Text(
                            stringResource(R.string.llm_kv_offload_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )

                        if (kvCacheEnabled) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                            Text(
                                stringResource(R.string.llm_kv_cache_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Cache Type K
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.llm_kv_cache_type_k), fontWeight = FontWeight.Medium)
                                Box {
                                    OutlinedButton(onClick = { showTypeKMenu = true }) {
                                        Text(kvCacheTypeK)
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(
                                        expanded = showTypeKMenu,
                                        onDismissRequest = { showTypeKMenu = false }
                                    ) {
                                        cacheTypes.forEach { type ->
                                            DropdownMenuItem(
                                                text = { Text(type) },
                                                onClick = {
                                                    settingsRepo.setServerKvCacheTypeK(type)
                                                    showTypeKMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Cache Type V
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.llm_kv_cache_type_v), fontWeight = FontWeight.Medium)
                                Box {
                                    OutlinedButton(onClick = { showTypeVMenu = true }) {
                                        Text(kvCacheTypeV)
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(
                                        expanded = showTypeVMenu,
                                        onDismissRequest = { showTypeVMenu = false }
                                    ) {
                                        cacheTypes.forEach { type ->
                                            DropdownMenuItem(
                                                text = { Text(type) },
                                                onClick = {
                                                    settingsRepo.setServerKvCacheTypeV(type)
                                                    showTypeVMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Cache Reuse
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.kv_cache_reuse), fontWeight = FontWeight.Medium)
                                Text(
                                    if (kvCacheReuse == 0) stringResource(R.string.llm_kv_cache_disabled) else "$kvCacheReuse",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = kvCacheReuse.toFloat(),
                                onValueChange = { settingsRepo.setServerKvCacheReuse(it.toInt()) },
                                valueRange = 0f..512f,
                                steps = 7  // 0, 64, 128, 192, 256, 320, 384, 448, 512
                            )
                        }
                    }
                }
            }
            
            // llama.cpp model loading policy
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("📥 " + stringResource(R.string.llm_load_mode_title), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.llm_load_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LlamaLoadModePicker(
                            selected = llamaLoadMode,
                            expanded = loadModeMenuExpanded,
                            onExpandedChange = { loadModeMenuExpanded = it },
                            onSelected = settingsRepo::setLlamaLoadMode
                        )
                    }
                }
            }
            
            // Advanced: Flash Attention
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.dist_flash_attention), fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.dist_flash_attention_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    stringResource(R.string.llm_flash_attention_opencl_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }
                            Switch(
                                checked = flashAttentionEnabled,
                                onCheckedChange = { settingsRepo.setFlashAttentionEnabled(it) }
                            )
                        }
                    }
                }
            }

            // Speculative Decoding
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.dist_speculative_title), fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.dist_speculative_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = speculativeEnabled,
                                onCheckedChange = { settingsRepo.setSpeculativeEnabled(it) }
                            )
                        }

                        if (speculativeEnabled) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                            val selectedSpeculativeModeLabel = when (speculativeMode) {
                                LlamaSpeculativeMode.DRAFT_MTP -> stringResource(R.string.dist_speculative_mode_mtp)
                                LlamaSpeculativeMode.DRAFT_DFLASH -> stringResource(R.string.dist_speculative_mode_dflash)
                                LlamaSpeculativeMode.DRAFT_DSPARK -> stringResource(R.string.dist_speculative_mode_dspark)
                                LlamaSpeculativeMode.DRAFT_SIMPLE -> stringResource(R.string.dist_speculative_mode_simple)
                                LlamaSpeculativeMode.NGRAM_MOD -> stringResource(R.string.dist_speculative_mode_ngram_mod)
                                LlamaSpeculativeMode.NGRAM_SIMPLE -> stringResource(R.string.dist_speculative_mode_ngram_simple)
                                LlamaSpeculativeMode.NGRAM_MAP_K -> stringResource(R.string.dist_speculative_mode_ngram_map_k)
                                LlamaSpeculativeMode.NGRAM_MAP_K4V -> stringResource(R.string.dist_speculative_mode_ngram_map_k4v)
                                LlamaSpeculativeMode.NGRAM_CACHE -> stringResource(R.string.dist_speculative_mode_ngram_cache)
                            }

                            Text(stringResource(R.string.dist_speculative_strategy_label), fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(8.dp))
                            ExposedDropdownMenuBox(
                                expanded = speculativeModeMenuExpanded,
                                onExpandedChange = { speculativeModeMenuExpanded = !speculativeModeMenuExpanded },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = selectedSpeculativeModeLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.dist_speculative_mode_label)) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = speculativeModeMenuExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    singleLine = true
                                )
                                ExposedDropdownMenu(
                                    expanded = speculativeModeMenuExpanded,
                                    onDismissRequest = { speculativeModeMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.dist_speculative_model_modes_label),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        onClick = {},
                                        enabled = false
                                    )
                                    listOf(
                                        LlamaSpeculativeMode.DRAFT_MTP,
                                        LlamaSpeculativeMode.DRAFT_DFLASH,
                                        LlamaSpeculativeMode.DRAFT_DSPARK,
                                        LlamaSpeculativeMode.DRAFT_SIMPLE
                                    ).forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    when (mode) {
                                                        LlamaSpeculativeMode.DRAFT_MTP -> stringResource(R.string.dist_speculative_mode_mtp)
                                                        LlamaSpeculativeMode.DRAFT_DFLASH -> stringResource(R.string.dist_speculative_mode_dflash)
                                                        LlamaSpeculativeMode.DRAFT_DSPARK -> stringResource(R.string.dist_speculative_mode_dspark)
                                                        else -> stringResource(R.string.dist_speculative_mode_simple)
                                                    }
                                                )
                                            },
                                            onClick = {
                                                settingsRepo.setSpeculativeMode(mode)
                                                speculativeModeMenuExpanded = false
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.dist_speculative_no_draft_modes_label),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        onClick = {},
                                        enabled = false
                                    )
                                    listOf(
                                        LlamaSpeculativeMode.NGRAM_MOD,
                                        LlamaSpeculativeMode.NGRAM_SIMPLE,
                                        LlamaSpeculativeMode.NGRAM_MAP_K
                                    ).forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    when (mode) {
                                                        LlamaSpeculativeMode.NGRAM_MOD -> stringResource(R.string.dist_speculative_mode_ngram_mod)
                                                        LlamaSpeculativeMode.NGRAM_SIMPLE -> stringResource(R.string.dist_speculative_mode_ngram_simple)
                                                        else -> stringResource(R.string.dist_speculative_mode_ngram_map_k)
                                                    }
                                                )
                                            },
                                            onClick = {
                                                settingsRepo.setSpeculativeMode(mode)
                                                speculativeModeMenuExpanded = false
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.dist_speculative_advanced_modes_label),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        onClick = {},
                                        enabled = false
                                    )
                                    listOf(
                                        LlamaSpeculativeMode.NGRAM_MAP_K4V,
                                        LlamaSpeculativeMode.NGRAM_CACHE
                                    ).forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    when (mode) {
                                                        LlamaSpeculativeMode.NGRAM_MAP_K4V -> stringResource(R.string.dist_speculative_mode_ngram_map_k4v)
                                                        else -> stringResource(R.string.dist_speculative_mode_ngram_cache)
                                                    }
                                                )
                                            },
                                            onClick = {
                                                settingsRepo.setSpeculativeMode(mode)
                                                speculativeModeMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            if (speculativeMode != LlamaSpeculativeMode.DRAFT_MTP) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = when (speculativeMode) {
                                    LlamaSpeculativeMode.DRAFT_DFLASH -> stringResource(R.string.dist_speculative_dflash_hint)
                                    LlamaSpeculativeMode.DRAFT_DSPARK -> stringResource(R.string.dist_speculative_dspark_hint)
                                    LlamaSpeculativeMode.DRAFT_SIMPLE -> stringResource(R.string.dist_speculative_simple_hint)
                                    LlamaSpeculativeMode.NGRAM_MOD -> stringResource(R.string.dist_speculative_ngram_mod_hint)
                                    LlamaSpeculativeMode.NGRAM_SIMPLE -> stringResource(R.string.dist_speculative_ngram_simple_hint)
                                    LlamaSpeculativeMode.NGRAM_MAP_K -> stringResource(R.string.dist_speculative_ngram_map_k_hint)
                                    LlamaSpeculativeMode.NGRAM_MAP_K4V -> stringResource(R.string.dist_speculative_ngram_map_k4v_hint)
                                    LlamaSpeculativeMode.NGRAM_CACHE -> stringResource(R.string.dist_speculative_ngram_cache_hint)
                                        LlamaSpeculativeMode.DRAFT_MTP -> ""
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            when (speculativeMode) {
                                LlamaSpeculativeMode.DRAFT_SIMPLE,
                                LlamaSpeculativeMode.DRAFT_DFLASH,
                                LlamaSpeculativeMode.DRAFT_DSPARK -> {
                                    var showDraftDeviceMenu by remember { mutableStateOf(false) }
                                    val draftMaxLabel = if (
                                        speculativeMode == LlamaSpeculativeMode.DRAFT_DFLASH ||
                                        speculativeMode == LlamaSpeculativeMode.DRAFT_DSPARK
                                    ) R.string.dist_speculative_block_size else R.string.dist_speculative_draft_max
                                    Text(stringResource(R.string.dist_speculative_draft_model), fontWeight = FontWeight.Medium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { showDraftSelector = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            effectiveDraftModelPath?.substringAfterLast("/") ?: stringResource(R.string.dist_speculative_select_draft),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (effectiveDraftModelPath != null) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        TextButton(
                                            onClick = { settingsRepo.setDraftModelPath(null) },
                                            modifier = Modifier.align(Alignment.End)
                                        ) {
                                            Text(stringResource(R.string.dist_speculative_clear_draft))
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        DraftIntTextField(
                                            value = draftMaxTokens,
                                            onValueChange = settingsRepo::setDraftMaxTokens,
                                            label = { Text(stringResource(draftMaxLabel)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        if (
                                            speculativeMode == LlamaSpeculativeMode.DRAFT_DFLASH ||
                                            speculativeMode == LlamaSpeculativeMode.DRAFT_DSPARK
                                        ) {
                                            Text(
                                                stringResource(R.string.dist_speculative_block_size_hint),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (speculativeMode == LlamaSpeculativeMode.DRAFT_SIMPLE) {
                                            DraftIntTextField(
                                                value = draftMinTokens,
                                                onValueChange = settingsRepo::setDraftMinTokens,
                                                label = { Text(stringResource(R.string.dist_speculative_draft_min)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true
                                            )

                                            DraftFloatTextField(
                                                value = draftPMin,
                                                onValueChange = settingsRepo::setDraftPMin,
                                                valueRange = 0f..1f,
                                                label = { Text(stringResource(R.string.dist_speculative_draft_p_min)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true
                                            )
                                        }

                                        DraftIntTextField(
                                            value = draftThreads,
                                            onValueChange = settingsRepo::setDraftThreads,
                                            label = { Text(stringResource(R.string.dist_speculative_draft_threads)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )

                                        DraftIntTextField(
                                            value = draftThreadsBatch,
                                            onValueChange = settingsRepo::setDraftThreadsBatch,
                                            label = { Text(stringResource(R.string.dist_speculative_draft_threads_batch)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )

                                        LlmModeDropdown(
                                            label = stringResource(R.string.llm_draft_device_mode),
                                            selected = llamaDraftDeviceMode,
                                            options = listOf(
                                                SettingsRepository.LLAMA_DRAFT_DEVICE_AUTO to stringResource(R.string.llm_backend_mode_auto),
                                                SettingsRepository.LLAMA_DRAFT_DEVICE_ACCELERATOR to stringResource(R.string.llm_draft_device_accelerator),
                                                SettingsRepository.LLAMA_DRAFT_DEVICE_CPU to stringResource(R.string.llm_draft_device_cpu)
                                            ),
                                            expanded = showDraftDeviceMenu,
                                            onExpandedChange = { showDraftDeviceMenu = it },
                                            onSelected = settingsRepo::setLlamaDraftDeviceMode
                                        )
                                        Text(
                                            stringResource(R.string.llm_draft_device_hint),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                        )
                                    }
                                }
                                LlamaSpeculativeMode.DRAFT_MTP -> {
                                    var showDraftDeviceMenu by remember { mutableStateOf(false) }
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    stringResource(R.string.general_mtp_use_draft_model_title),
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    stringResource(R.string.general_mtp_use_draft_model_desc),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Switch(
                                                checked = mtpUseDraftModel,
                                                onCheckedChange = settingsRepo::setMtpUseDraftModel
                                            )
                                        }

                                        if (mtpUseDraftModel) {
                                            Text(stringResource(R.string.dist_speculative_draft_model), fontWeight = FontWeight.Medium)
                                            OutlinedButton(
                                                onClick = { showDraftSelector = true },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    effectiveDraftModelPath?.substringAfterLast("/") ?: stringResource(R.string.dist_speculative_select_draft),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            if (effectiveDraftModelPath != null) {
                                                TextButton(
                                                    onClick = { settingsRepo.setDraftModelPath(null) },
                                                    modifier = Modifier.align(Alignment.End)
                                                ) {
                                                    Text(stringResource(R.string.dist_speculative_clear_draft))
                                                }
                                            }

                                            DraftIntTextField(
                                                value = draftThreads,
                                                onValueChange = settingsRepo::setDraftThreads,
                                                label = { Text(stringResource(R.string.dist_speculative_draft_threads)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true
                                            )

                                            DraftIntTextField(
                                                value = draftThreadsBatch,
                                                onValueChange = settingsRepo::setDraftThreadsBatch,
                                                label = { Text(stringResource(R.string.dist_speculative_draft_threads_batch)) },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true
                                            )
                                        }

                                        LlmModeDropdown(
                                            label = stringResource(R.string.llm_draft_device_mode),
                                            selected = llamaDraftDeviceMode,
                                            options = listOf(
                                                SettingsRepository.LLAMA_DRAFT_DEVICE_AUTO to stringResource(R.string.llm_backend_mode_auto),
                                                SettingsRepository.LLAMA_DRAFT_DEVICE_ACCELERATOR to stringResource(R.string.llm_draft_device_accelerator),
                                                SettingsRepository.LLAMA_DRAFT_DEVICE_CPU to stringResource(R.string.llm_draft_device_cpu)
                                            ),
                                            expanded = showDraftDeviceMenu,
                                            onExpandedChange = { showDraftDeviceMenu = it },
                                            onSelected = settingsRepo::setLlamaDraftDeviceMode
                                        )
                                        Text(
                                            stringResource(R.string.llm_draft_device_hint),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                        )

                                        DraftIntTextField(
                                            value = mtpDraftMaxTokens,
                                            onValueChange = settingsRepo::setMtpDraftMaxTokens,
                                            label = { Text(stringResource(R.string.dist_speculative_mtp_block_size)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        DraftIntTextField(
                                            value = mtpDraftMinTokens,
                                            onValueChange = settingsRepo::setMtpDraftMinTokens,
                                            label = { Text(stringResource(R.string.dist_speculative_draft_min)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )

                                        DraftFloatTextField(
                                            value = mtpDraftPMin,
                                            onValueChange = settingsRepo::setMtpDraftPMin,
                                            valueRange = 0f..1f,
                                            label = { Text(stringResource(R.string.dist_speculative_draft_p_min)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                    }
                                }
                                LlamaSpeculativeMode.NGRAM_MOD -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(stringResource(R.string.dist_speculative_ngram_params), fontWeight = FontWeight.Medium)
                                        DraftIntTextField(
                                            value = ngramModNMatch,
                                            onValueChange = settingsRepo::setNgramModNMatch,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_mod_match)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        DraftIntTextField(
                                            value = ngramModNMin,
                                            onValueChange = settingsRepo::setNgramModNMin,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_mod_min)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        DraftIntTextField(
                                            value = ngramModNMax,
                                            onValueChange = settingsRepo::setNgramModNMax,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_mod_max)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                    }
                                }
                                LlamaSpeculativeMode.NGRAM_SIMPLE -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(stringResource(R.string.dist_speculative_ngram_params), fontWeight = FontWeight.Medium)
                                        DraftIntTextField(
                                            value = ngramSimpleSizeN,
                                            onValueChange = settingsRepo::setNgramSimpleSizeN,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_simple_size_n)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        DraftIntTextField(
                                            value = ngramSimpleSizeM,
                                            onValueChange = settingsRepo::setNgramSimpleSizeM,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_simple_size_m)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        DraftIntTextField(
                                            value = ngramSimpleMinHits,
                                            onValueChange = settingsRepo::setNgramSimpleMinHits,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_simple_min_hits)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                    }
                                }
                                LlamaSpeculativeMode.NGRAM_MAP_K -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(stringResource(R.string.dist_speculative_ngram_params), fontWeight = FontWeight.Medium)
                                        DraftIntTextField(
                                            value = ngramMapKSizeN,
                                            onValueChange = settingsRepo::setNgramMapKSizeN,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_map_k_size_n)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        DraftIntTextField(
                                            value = ngramMapKSizeM,
                                            onValueChange = settingsRepo::setNgramMapKSizeM,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_map_k_size_m)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        DraftIntTextField(
                                            value = ngramMapKMinHits,
                                            onValueChange = settingsRepo::setNgramMapKMinHits,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_map_k_min_hits)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                    }
                                }
                                LlamaSpeculativeMode.NGRAM_MAP_K4V -> {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(stringResource(R.string.dist_speculative_ngram_params), fontWeight = FontWeight.Medium)
                                        DraftIntTextField(
                                            value = ngramMapK4VSizeN,
                                            onValueChange = settingsRepo::setNgramMapK4VSizeN,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_map_k4v_size_n)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        DraftIntTextField(
                                            value = ngramMapK4VSizeM,
                                            onValueChange = settingsRepo::setNgramMapK4VSizeM,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_map_k4v_size_m)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                        DraftIntTextField(
                                            value = ngramMapK4VMinHits,
                                            onValueChange = settingsRepo::setNgramMapK4VMinHits,
                                            label = { Text(stringResource(R.string.dist_speculative_ngram_map_k4v_min_hits)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )
                                    }
                                }
                                LlamaSpeculativeMode.NGRAM_CACHE -> {
                                    Text(
                                        stringResource(R.string.dist_speculative_ngram_cache_params_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // Vision Settings
            if (hasVisionCapability) {
                item {
                    // val selectedMmprojPath by settingsRepo.selectedMmprojPath.collectAsState() // Moved to top
                    var showMmprojSelector by remember { mutableStateOf(false) }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("👁️ " + stringResource(R.string.llm_vision), fontWeight = FontWeight.Bold)
                                    Text(
                                        stringResource(R.string.llm_vision_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = enableVision,
                                    onCheckedChange = { settingsRepo.setEnableVision(it) }
                                )
                            }
                            
                            // Mmproj selector when vision is enabled
                            if (enableVision && visionProjectorModels.isNotEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.llm_vision_model), fontWeight = FontWeight.Medium)
                                        Text(
                                            selectedMmprojPath?.substringAfterLast("/") ?: stringResource(R.string.llm_vision_not_selected),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { showMmprojSelector = true },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(if (selectedMmprojPath != null) stringResource(R.string.action_change) else stringResource(R.string.action_select))
                                    }
                                }
                            }
                        }
                    }
                    
                    // Mmproj selector dialog
                    if (showMmprojSelector) {
                        AlertDialog(
                            onDismissRequest = { showMmprojSelector = false },
                            title = { Text(stringResource(R.string.llm_vision_select_title), fontWeight = FontWeight.Bold) },
                            text = {
                                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                    items(visionProjectorModels) { model ->
                                        Surface(
                                            onClick = {
                                                settingsRepo.setSelectedMmprojPath(model.path)
                                                showMmprojSelector = false
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (model.path == selectedMmprojPath)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surface
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.secondary)
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(model.filename, fontWeight = FontWeight.Medium)
                                                    Text(
                                                        "${model.sizeBytes / (1024 * 1024)} MB",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showMmprojSelector = false }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            },
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }
            } // End of Vision Settings

            item {
                SpeculativeRunHistoryCard(
                    runs = speculativeRuns,
                    onRename = { run, name ->
                        scope.launch {
                            db.llamaSpeculativeRunDao().renameRun(run.id, name.takeIf { it.isNotBlank() })
                        }
                    },
                    onToggleSaved = { run ->
                        scope.launch {
                            db.llamaSpeculativeRunDao().setSavedForever(run.id, !run.savedForever)
                        }
                    },
                    onDelete = { run ->
                        scope.launch {
                            db.llamaSpeculativeRunDao().deleteRun(run.id)
                        }
                    }
                )
            }
        } // End of LazyColumn
    } // End of Scaffold content
    
    // Save Command Dialog
    if (showSaveCommandDialog) {
        AlertDialog(
            onDismissRequest = { showSaveCommandDialog = false },
            title = { Text(stringResource(R.string.dist_save_command_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.dist_save_command_desc), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = saveCommandName,
                        onValueChange = { saveCommandName = it; selectedSaveCommandId = null },
                        label = { Text(stringResource(R.string.dist_command_preset_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.saved_command_update_existing), style = MaterialTheme.typography.labelLarge)
                    savedCommands.forEach { command ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedSaveCommandId = command.id
                                saveCommandName = command.name
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedSaveCommandId == command.id, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(command.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val requiresDraftSelection =
                            speculativeMode.requiresDraftModel ||
                                (speculativeMode == LlamaSpeculativeMode.DRAFT_MTP && mtpUseDraftModel)
                        if (speculativeEnabled &&
                            requiresDraftSelection &&
                            effectiveDraftModelPath.isNullOrBlank()
                        ) {
                            android.widget.Toast.makeText(context, resources.getString(R.string.dist_speculative_missing_required_draft), android.widget.Toast.LENGTH_SHORT).show()
                        } else if (saveCommandName.isNotBlank() && selectedModelPath != null) {
                            val existing = savedCommands.firstOrNull {
                                it.name.equals(saveCommandName.trim(), ignoreCase = true)
                            }
                            if (selectedSaveCommandId == null && existing != null) {
                                selectedSaveCommandId = existing.id
                                showOverwriteSavedCommandDialog = true
                            } else {
                                val cmd = savedCommandFromLaunchProfile(
                                    name = saveCommandName.trim(),
                                    profile = LlamaServerLaunchProfile.capture(settingsRepo).copy(
                                        draftModelPath = effectiveDraftModelPath
                                    ),
                                    id = selectedSaveCommandId ?: 0L
                                )
                                scope.launch { db.savedCommandDao().insertCommand(cmd) }
                                val feedback = if (selectedSaveCommandId == null) {
                                    R.string.dist_command_saved
                                } else {
                                    R.string.saved_command_overwritten
                                }
                                android.widget.Toast.makeText(context, resources.getString(feedback), android.widget.Toast.LENGTH_SHORT).show()
                                showSaveCommandDialog = false
                                saveCommandName = ""
                                selectedSaveCommandId = null
                            }
                        } else if (selectedModelPath == null) {
                            android.widget.Toast.makeText(context, resources.getString(R.string.llm_select_model), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = saveCommandName.isNotBlank()
                ) {
                    Text(stringResource(R.string.dist_save_command))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveCommandDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showOverwriteSavedCommandDialog) {
        AlertDialog(
            onDismissRequest = { showOverwriteSavedCommandDialog = false },
            title = { Text(stringResource(R.string.saved_command_overwrite_title)) },
            text = { Text(stringResource(R.string.saved_command_overwrite_message, saveCommandName)) },
            confirmButton = {
                Button(onClick = {
                    val cmd = savedCommandFromLaunchProfile(
                        name = saveCommandName.trim(),
                        profile = LlamaServerLaunchProfile.capture(settingsRepo),
                        id = selectedSaveCommandId ?: 0L
                    )
                    scope.launch { db.savedCommandDao().insertCommand(cmd) }
                    android.widget.Toast.makeText(context, resources.getString(R.string.saved_command_overwritten), android.widget.Toast.LENGTH_SHORT).show()
                    showOverwriteSavedCommandDialog = false
                    showSaveCommandDialog = false
                    saveCommandName = ""
                    selectedSaveCommandId = null
                }) { Text(stringResource(R.string.saved_command_overwrite)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverwriteSavedCommandDialog = false
                    selectedSaveCommandId = null
                }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // Load / Edit Command Dialog
    if (showLoadCommandDialog) {
        AlertDialog(
            onDismissRequest = { showLoadCommandDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.dist_load_command))
                    IconButton(onClick = { showLoadCommandDialog = false }) {
                        Icon(Icons.Default.Close, null)
                    }
                }
            },
            text = {
                if (savedCommands.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.dist_no_commands_saved), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(savedCommands) { cmd ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f).clickable {
                                            // The versioned profile owns every launch-affecting setting.
                                            // launchProfile() converts historical rows when JSON is absent.
                                            LlamaServerLaunchProfile.restore(cmd.launchProfile(), settingsRepo)
                                            
                                            settingsRepo.setLoadedCommandId(cmd.id)
                                            
                                            android.widget.Toast.makeText(context, resources.getString(R.string.dist_command_loaded), android.widget.Toast.LENGTH_SHORT).show()
                                            showLoadCommandDialog = false
                                        }.padding(8.dp)
                                    ) {
                                        Text(cmd.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(stringResource(R.string.model_filename_label, cmd.modelPath.substringAfterLast("/")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    
                                    Row {
                                        IconButton(onClick = { showCommandPreview = cmd }) {
                                            Icon(Icons.Default.Edit, stringResource(R.string.dist_edit_command))
                                        }
                                        IconButton(onClick = { 
                                            scope.launch {
                                                db.savedCommandDao().deleteCommand(cmd)
                                            }
                                        }) {
                                            Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Command Editor Preview Dialog
    showCommandPreview?.let { cmd ->
        val commandProfile = remember(cmd.id, cmd.launchProfileJson) { cmd.launchProfile() }
        var editName by remember(cmd.id) { mutableStateOf(cmd.name) }
        var editTemplate by remember(cmd.id, cmd.launchProfileJson) {
            mutableStateOf(commandProfile.commandTemplate.orEmpty())
        }
        var editFlags by remember(cmd.id, cmd.launchProfileJson) {
            mutableStateOf(commandProfile.customFlags.orEmpty())
        }
        
        AlertDialog(
            onDismissRequest = { showCommandPreview = null },
            title = { Text(stringResource(R.string.dist_edit_command)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text(stringResource(R.string.dist_command_preset_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editTemplate,
                        onValueChange = { editTemplate = it },
                        label = { Text(stringResource(R.string.command_template_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        supportingText = {
                            Text(stringResource(R.string.command_template_placeholders))
                        }
                    )
                    OutlinedTextField(
                        value = editFlags,
                        onValueChange = { editFlags = it },
                        label = { Text(stringResource(R.string.dist_advanced_custom_flags)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val updatedProfile = commandProfile.copy(
                        commandTemplate = editTemplate.takeIf { it.isNotBlank() },
                        customFlags = editFlags.takeIf { it.isNotBlank() }
                    )
                    scope.launch {
                        db.savedCommandDao().insertCommand(
                            savedCommandFromLaunchProfile(
                                name = editName.trim(),
                                profile = updatedProfile,
                                id = cmd.id
                            )
                        )
                    }
                    android.widget.Toast.makeText(context, resources.getString(R.string.saved_command_overwritten), android.widget.Toast.LENGTH_SHORT).show()
                    showCommandPreview = null
                }, enabled = editName.isNotBlank()) {
                    Text(stringResource(R.string.dist_save_command))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCommandPreview = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Model Selector Dialog
    if (showLlmSelector) {
        AlertDialog(
            onDismissRequest = { showLlmSelector = false },
            title = { Text(stringResource(R.string.llm_select_model), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(llmModels) { model ->
                        Surface(
                            onClick = {
                                settingsRepo.setSelectedModelPath(model.path)
                                showLlmSelector = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (model.path == selectedModelPath)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(model.filename, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${model.sizeBytes / (1024 * 1024)} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLlmSelector = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showLoraSelector) {
        AlertDialog(
            onDismissRequest = { showLoraSelector = false },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.llm_lora_stack_select_title),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        pluralStringResource(R.plurals.llm_lora_stack_selected_count, loraDialogDraft.size, loraDialogDraft.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (loraAdapters.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.llm_lora_stack_no_installed),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(loraAdapters) { model ->
                        val selected = loraDialogDraft.any { lora ->
                            loraPathsMatch(lora.path, model.path)
                        }
                        Surface(
                            onClick = {
                                loraDialogDraft = if (selected) {
                                    // Removing a selected adapter removes all
                                    // occurrences of that path. Existing
                                    // migrated duplicates remain untouched as
                                    // long as the user does not toggle them.
                                    loraDialogDraft.filterNot { lora ->
                                        loraPathsMatch(lora.path, model.path)
                                    }
                                } else {
                                    // A newly selected path can only be added
                                    // once; old migrated duplicate rows remain
                                    // representable in the ordered editor.
                                    loraDialogDraft + LlamaLoraSpec(
                                        path = model.path,
                                        strength = 1f
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        loraDialogDraft = if (checked) {
                                            if (loraDialogDraft.any { lora -> loraPathsMatch(lora.path, model.path) }) {
                                                loraDialogDraft
                                            } else {
                                                loraDialogDraft + LlamaLoraSpec(
                                                    path = model.path,
                                                    strength = 1f
                                                )
                                            }
                                        } else {
                                            loraDialogDraft.filterNot { lora ->
                                                loraPathsMatch(lora.path, model.path)
                                            }
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        model.filename,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${model.sizeBytes / (1024 * 1024)} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        persistLoraStack(loraDialogDraft)
                        showLoraSelector = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.llm_lora_stack_select_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLoraSelector = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Draft Model Selector Dialog
    if (showDraftSelector) {
        AlertDialog(
            onDismissRequest = { showDraftSelector = false },
            title = { Text(stringResource(draftSelectorTitleRes), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(draftSelectorModels) { model ->
                        Surface(
                            onClick = {
                                settingsRepo.setDraftModelPath(model.path)
                                showDraftSelector = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (model.path == effectiveDraftModelPath)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(model.filename, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${model.sizeBytes / (1024 * 1024)} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDraftSelector = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            dismissButton = {
                if (effectiveDraftModelPath != null) {
                    TextButton(
                        onClick = {
                            settingsRepo.setDraftModelPath(null)
                            showDraftSelector = false
                        }
                    ) {
                        Text(stringResource(R.string.dist_speculative_clear_draft))
                    }
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}
