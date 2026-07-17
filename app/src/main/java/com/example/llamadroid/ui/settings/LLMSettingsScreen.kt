package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SavedCommand
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.service.LlamaSpeculativeMode
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppScreenScaffold
import kotlinx.coroutines.launch

private typealias SavedCommandEntity = SavedCommand

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
    valueRange: ClosedFloatingPointRange<Float>? = null
) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = buildString {
                var seenDot = false
                it.forEach { ch ->
                    if (ch.isDigit()) append(ch)
                    if (ch == '.' && !seenDot) {
                        append(ch)
                        seenDot = true
                    }
                }
            }
            draft.toFloatOrNull()?.let { parsed ->
                if (valueRange?.contains(parsed) != false) onValueChange(parsed)
            }
        },
        modifier = modifier,
        label = label,
        singleLine = singleLine
    )
}

/**
 * LLM/Chat Settings - Threads, Context Size, Temperature, Vision
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LLMSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    
    val threads by settingsRepo.threads.collectAsState()
    val ctxSize by settingsRepo.contextSize.collectAsState()
    val temp by settingsRepo.temperature.collectAsState()
    val selectedModelPath by settingsRepo.selectedModelPath.collectAsState()
    val enableVision by settingsRepo.enableVision.collectAsState()
    
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
    val serverParallel by settingsRepo.serverParallel.collectAsState()
    val serverCacheRam by settingsRepo.serverCacheRam.collectAsState()
    
    // Custom Commands Additions
    val customFlags by settingsRepo.customFlags.collectAsState()
    var customFlagsText by remember(customFlags) { mutableStateOf(customFlags) }
    val customCommandTemplate by settingsRepo.customCommandTemplate.collectAsState()
    var customCommandTemplateText by remember(customCommandTemplate) { mutableStateOf(customCommandTemplate) }
    val kvCacheEnabled by settingsRepo.serverKvCacheEnabled.collectAsState()
    val kvCacheTypeK by settingsRepo.serverKvCacheTypeK.collectAsState()
    val kvCacheTypeV by settingsRepo.serverKvCacheTypeV.collectAsState()
    val kvCacheReuse by settingsRepo.serverKvCacheReuse.collectAsState()
    
    var showSaveCommandDialog by remember { mutableStateOf(false) }
    var speculativeModeMenuExpanded by remember { mutableStateOf(false) }
    var saveCommandName by remember { mutableStateOf("") }
    var showLoadCommandDialog by remember { mutableStateOf(false) }
    var showCommandPreview by remember { mutableStateOf<SavedCommandEntity?>(null) }
    
    val savedCommands by db.savedCommandDao()
        .getCommandsByScope(SavedCommandScopes.GENERAL)
        .collectAsState(initial = emptyList())
    val speculativeRuns by db.llamaSpeculativeRunDao().observeRuns().collectAsState(initial = emptyList())
    // val scope = rememberCoroutineScope() // Duplicate declaration, removed

    val llmModels by db.modelDao().getModelsByType(ModelType.LLM).collectAsState(initial = emptyList())
    val visionProjectorModels by db.modelDao().getModelsByType(ModelType.VISION_PROJECTOR).collectAsState(initial = emptyList())
    
    val selectedModel = llmModels.find { it.path == selectedModelPath }
    // Only show vision toggle if selected model has isVision=true
    val hasVisionCapability = selectedModel?.isVision == true && visionProjectorModels.isNotEmpty()
    
    val selectedMmprojPath by settingsRepo.selectedMmprojPath.collectAsState()

    // Only disable vision when models are loaded AND no vision capability
    // This prevents race condition where llmModels is initially empty
    LaunchedEffect(hasVisionCapability, llmModels) {
        if (llmModels.isNotEmpty() && !hasVisionCapability && enableVision) {
            settingsRepo.setEnableVision(false)
        }
    }
    
    var showLlmSelector by remember { mutableStateOf(false) }
    var showDraftSelector by remember { mutableStateOf(false) }
    
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
                                onClick = { showSaveCommandDialog = true },
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
                            value = serverParallel,
                            onValueChange = settingsRepo::setServerParallel,
                            valueRange = 1..512,
                            label = { Text(stringResource(R.string.dist_advanced_parallel)) },
                            placeholder = { Text(stringResource(R.string.llm_optional_flag_blank)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
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
                            Text(String.format("%.1f", temp), color = MaterialTheme.colorScheme.primary)
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
            
            // Disable Memory Mapping
            item {
                val disableMmap by settingsRepo.lowMemoryMode.collectAsState()
                
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text("📥 " + stringResource(R.string.llm_mmap_title), fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.llm_mmap_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = disableMmap,
                                onCheckedChange = { settingsRepo.setLowMemoryMode(it) }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (disableMmap) 
                                stringResource(R.string.llm_mmap_on)
                            else 
                                stringResource(R.string.llm_mmap_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (disableMmap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
                                        LlamaSpeculativeMode.DRAFT_SIMPLE
                                    ).forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    when (mode) {
                                                        LlamaSpeculativeMode.DRAFT_MTP -> stringResource(R.string.dist_speculative_mode_mtp)
                                                        LlamaSpeculativeMode.DRAFT_DFLASH -> stringResource(R.string.dist_speculative_mode_dflash)
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

                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = when (speculativeMode) {
                                    LlamaSpeculativeMode.DRAFT_MTP -> stringResource(R.string.general_mtp_decoding_hint)
                                    LlamaSpeculativeMode.DRAFT_DFLASH -> stringResource(R.string.dist_speculative_dflash_hint)
                                    LlamaSpeculativeMode.DRAFT_SIMPLE -> stringResource(R.string.dist_speculative_simple_hint)
                                    LlamaSpeculativeMode.NGRAM_MOD -> stringResource(R.string.dist_speculative_ngram_mod_hint)
                                    LlamaSpeculativeMode.NGRAM_SIMPLE -> stringResource(R.string.dist_speculative_ngram_simple_hint)
                                    LlamaSpeculativeMode.NGRAM_MAP_K -> stringResource(R.string.dist_speculative_ngram_map_k_hint)
                                    LlamaSpeculativeMode.NGRAM_MAP_K4V -> stringResource(R.string.dist_speculative_ngram_map_k4v_hint)
                                    LlamaSpeculativeMode.NGRAM_CACHE -> stringResource(R.string.dist_speculative_ngram_cache_hint)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            when (speculativeMode) {
                                LlamaSpeculativeMode.DRAFT_SIMPLE,
                                LlamaSpeculativeMode.DRAFT_DFLASH -> {
                                    Text(stringResource(R.string.dist_speculative_draft_model), fontWeight = FontWeight.Medium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { showDraftSelector = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            draftModelPath?.substringAfterLast("/") ?: stringResource(R.string.dist_speculative_select_draft),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (draftModelPath != null) {
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
                                            label = { Text(stringResource(R.string.dist_speculative_draft_max)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true
                                        )

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
                                    }
                                }
                                LlamaSpeculativeMode.DRAFT_MTP -> {
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
                                                    draftModelPath?.substringAfterLast("/") ?: stringResource(R.string.dist_speculative_select_draft),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            if (draftModelPath != null) {
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

                                        DraftIntTextField(
                                            value = mtpDraftMaxTokens,
                                            onValueChange = settingsRepo::setMtpDraftMaxTokens,
                                            label = { Text(stringResource(R.string.dist_speculative_draft_max)) },
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
                Column {
                    Text(stringResource(R.string.dist_save_command_desc), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = saveCommandName,
                        onValueChange = { saveCommandName = it },
                        label = { Text(stringResource(R.string.dist_command_preset_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (speculativeEnabled &&
                            speculativeMode.requiresDraftModel &&
                            draftModelPath.isNullOrBlank()
                        ) {
                            android.widget.Toast.makeText(context, context.getString(R.string.dist_speculative_missing_required_draft), android.widget.Toast.LENGTH_SHORT).show()
                        } else if (saveCommandName.isNotBlank() && selectedModelPath != null) {
                            val cmd = SavedCommandEntity(
                                name = saveCommandName,
                                commandTemplate = customCommandTemplateText,
                                scope = SavedCommandScopes.GENERAL,
                                modelPath = selectedModelPath ?: "",
                                contextSize = ctxSize,
                                batchSize = serverBatchSize,
                                temperature = temp,
                                threads = threads,
                                host = if (settingsRepo.remoteAccess.value) "0.0.0.0" else "127.0.0.1",
                                speculativeEnabled = speculativeEnabled,
                                speculativeMode = speculativeMode.flagValue,
                                draftModelPath = draftModelPath,
                                draftMax = draftMaxTokens,
                                draftMin = draftMinTokens,
                                draftPMin = draftPMin,
                                draftThreads = draftThreads,
                                draftThreadsBatch = draftThreadsBatch,
                                ngramModNMatch = ngramModNMatch,
                                ngramModNMin = ngramModNMin,
                                ngramModNMax = ngramModNMax,
                                ngramSimpleSizeN = ngramSimpleSizeN,
                                ngramSimpleSizeM = ngramSimpleSizeM,
                                ngramSimpleMinHits = ngramSimpleMinHits,
                                ngramMapKSizeN = ngramMapKSizeN,
                                ngramMapKSizeM = ngramMapKSizeM,
                                ngramMapKMinHits = ngramMapKMinHits,
                                ngramMapK4VSizeN = ngramMapK4VSizeN,
                                ngramMapK4VSizeM = ngramMapK4VSizeM,
                                ngramMapK4VMinHits = ngramMapK4VMinHits,
                                nativeToolsEnabled = llamaNativeToolsEnabled,
                                parallel = serverParallel,
                                cacheRam = serverCacheRam,
                                customFlags = customFlagsText,
                                flashAttention = flashAttentionEnabled,
                                kvCacheEnabled = kvCacheEnabled,
                                kvCacheTypeK = kvCacheTypeK,
                                kvCacheTypeV = kvCacheTypeV,
                                kvCacheReuse = kvCacheReuse,
                                masterRamMB = 4096,
                                workersListStr = "",
                                enableVision = enableVision,
                                mmprojPath = selectedMmprojPath
                            )
                            scope.launch {
                                db.savedCommandDao().insertCommand(cmd)
                            }
                            android.widget.Toast.makeText(context, context.getString(R.string.dist_command_saved), android.widget.Toast.LENGTH_SHORT).show()
                            showSaveCommandDialog = false
                            saveCommandName = ""
                        } else if (selectedModelPath == null) {
                            android.widget.Toast.makeText(context, context.getString(R.string.llm_select_model), android.widget.Toast.LENGTH_SHORT).show()
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
                                            // Apply to UI state mapped variables
                                            settingsRepo.setSelectedModelPath(cmd.modelPath)
                                            settingsRepo.setContextSize(cmd.contextSize)
                                            settingsRepo.setTemperature(cmd.temperature)
                                            settingsRepo.setThreads(cmd.threads)
                                            settingsRepo.setServerBatchSize(cmd.batchSize)
                                            
                                            // Speculative
                                            settingsRepo.setSpeculativeEnabled(cmd.speculativeEnabled)
                                            settingsRepo.setSpeculativeMode(LlamaSpeculativeMode.fromFlagValue(cmd.speculativeMode))
                                            settingsRepo.setDraftModelPath(cmd.draftModelPath)
                                            settingsRepo.setDraftMaxTokens(cmd.draftMax)
                                            settingsRepo.setDraftMinTokens(cmd.draftMin)
                                            settingsRepo.setDraftPMin(cmd.draftPMin)
                                            settingsRepo.setDraftThreads(cmd.draftThreads)
                                            settingsRepo.setDraftThreadsBatch(cmd.draftThreadsBatch)
                                            settingsRepo.setNgramModNMatch(cmd.ngramModNMatch)
                                            settingsRepo.setNgramModNMin(cmd.ngramModNMin)
                                            settingsRepo.setNgramModNMax(cmd.ngramModNMax)
                                            settingsRepo.setNgramSimpleSizeN(cmd.ngramSimpleSizeN)
                                            settingsRepo.setNgramSimpleSizeM(cmd.ngramSimpleSizeM)
                                            settingsRepo.setNgramSimpleMinHits(cmd.ngramSimpleMinHits)
                                            settingsRepo.setNgramMapKSizeN(cmd.ngramMapKSizeN)
                                            settingsRepo.setNgramMapKSizeM(cmd.ngramMapKSizeM)
                                            settingsRepo.setNgramMapKMinHits(cmd.ngramMapKMinHits)
                                            settingsRepo.setNgramMapK4VSizeN(cmd.ngramMapK4VSizeN)
                                            settingsRepo.setNgramMapK4VSizeM(cmd.ngramMapK4VSizeM)
                                            settingsRepo.setNgramMapK4VMinHits(cmd.ngramMapK4VMinHits)
                                            settingsRepo.setLlamaNativeToolsEnabled(cmd.nativeToolsEnabled)
                                            
                                            // Advanced & Vision
                                            settingsRepo.setCustomCommandTemplate(cmd.commandTemplate)
                                            settingsRepo.setCustomFlags(cmd.customFlags)
                                            settingsRepo.setServerParallel(cmd.parallel)
                                            settingsRepo.setServerCacheRam(cmd.cacheRam)
                                            settingsRepo.setFlashAttentionEnabled(cmd.flashAttention)
                                            settingsRepo.setServerKvCacheEnabled(cmd.kvCacheEnabled)
                                            settingsRepo.setServerKvCacheTypeK(cmd.kvCacheTypeK)
                                            settingsRepo.setServerKvCacheTypeV(cmd.kvCacheTypeV)
                                            settingsRepo.setServerKvCacheReuse(cmd.kvCacheReuse)
                                            settingsRepo.setRemoteAccess(cmd.host == "0.0.0.0")
                                            settingsRepo.setEnableVision(cmd.enableVision)
                                            settingsRepo.setSelectedMmprojPath(cmd.mmprojPath)
                                            
                                            settingsRepo.setLoadedCommandId(cmd.id)
                                            
                                            android.widget.Toast.makeText(context, context.getString(R.string.dist_command_loaded), android.widget.Toast.LENGTH_SHORT).show()
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
        var editName by remember(cmd.id) { mutableStateOf(cmd.name) }
        var editTemplate by remember(cmd.id) { mutableStateOf(cmd.commandTemplate) }
        var editFlags by remember(cmd.id) { mutableStateOf(cmd.customFlags) }
        
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
                    scope.launch {
                        db.savedCommandDao().insertCommand(
                            cmd.copy(
                                name = editName,
                                commandTemplate = editTemplate,
                                customFlags = editFlags
                            )
                        )
                    }
                    showCommandPreview = null
                }) {
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

    // Draft Model Selector Dialog
    if (showDraftSelector) {
        AlertDialog(
            onDismissRequest = { showDraftSelector = false },
            title = { Text(stringResource(R.string.llm_select_model), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(llmModels) { model ->
                        Surface(
                            onClick = {
                                settingsRepo.setDraftModelPath(model.path)
                                showDraftSelector = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (model.path == draftModelPath)
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
                if (draftModelPath != null) {
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
