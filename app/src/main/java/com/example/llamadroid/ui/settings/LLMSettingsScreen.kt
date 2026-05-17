package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.SavedCommandEntity
import com.example.llamadroid.data.db.SavedCommandScopes
import androidx.compose.foundation.clickable
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.DraftFloatTextField
import com.example.llamadroid.ui.components.DraftIntTextField
import kotlinx.coroutines.launch

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
    val draftModelPath by settingsRepo.draftModelPath.collectAsState()
    val draftMaxTokens by settingsRepo.draftMaxTokens.collectAsState()
    val draftMinTokens by settingsRepo.draftMinTokens.collectAsState()
    val draftPMin by settingsRepo.draftPMin.collectAsState()
    val flashAttentionEnabled by settingsRepo.flashAttentionEnabled.collectAsState()
    val mtpDecodingEnabled by settingsRepo.mtpDecodingEnabled.collectAsState()
    val mtpDraftMaxTokens by settingsRepo.mtpDraftMaxTokens.collectAsState()
    
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
    var saveCommandName by remember { mutableStateOf("") }
    var showLoadCommandDialog by remember { mutableStateOf(false) }
    var showCommandPreview by remember { mutableStateOf<SavedCommandEntity?>(null) }
    
    val savedCommands by db.savedCommandDao()
        .getCommandsByScope(SavedCommandScopes.GENERAL)
        .collectAsState(initial = emptyList())
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
    ) { _ ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showLoadCommandDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Menu, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.dist_load_command))
                            }
                            
                            Button(
                                onClick = { showSaveCommandDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Star, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.dist_save_command))
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

            // MTP Decoding
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.general_mtp_decoding_title), fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.general_mtp_decoding_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = mtpDecodingEnabled,
                                onCheckedChange = settingsRepo::setMtpDecodingEnabled
                            )
                        }
                        Text(
                            stringResource(R.string.general_mtp_decoding_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                        if (mtpDecodingEnabled) {
                            DraftIntTextField(
                                value = mtpDraftMaxTokens,
                                onValueChange = settingsRepo::setMtpDraftMaxTokens,
                                label = { Text(stringResource(R.string.general_mtp_draft_max_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
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

                            // Draft Model Selection
                            Text(stringResource(R.string.dist_speculative_draft_model), fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showDraftSelector = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    draftModelPath?.substringAfterLast("/") ?: stringResource(R.string.dist_speculative_select_draft),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Parameters Grid
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Max tokens
                                DraftIntTextField(
                                    value = draftMaxTokens,
                                    onValueChange = settingsRepo::setDraftMaxTokens,
                                    label = { Text(stringResource(R.string.dist_speculative_draft_max)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )

                                // Min tokens
                                DraftIntTextField(
                                    value = draftMinTokens,
                                    onValueChange = settingsRepo::setDraftMinTokens,
                                    label = { Text(stringResource(R.string.dist_speculative_draft_min)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )

                                // p-min
                                DraftFloatTextField(
                                    value = draftPMin,
                                    onValueChange = settingsRepo::setDraftPMin,
                                    label = { Text(stringResource(R.string.dist_speculative_draft_p_min)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
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
                        if (saveCommandName.isNotBlank() && selectedModelPath != null) {
                            val cmd = SavedCommandEntity(
                                name = saveCommandName,
                                commandTemplate = customCommandTemplateText,
                                scope = SavedCommandScopes.GENERAL,
                                modelPath = selectedModelPath ?: "",
                                contextSize = ctxSize,
                                batchSize = 512, // Default or generic
                                temperature = temp,
                                threads = threads,
                                host = if (settingsRepo.remoteAccess.value) "0.0.0.0" else "127.0.0.1",
                                speculativeEnabled = speculativeEnabled,
                                draftModelPath = draftModelPath,
                                draftMax = draftMaxTokens,
                                draftMin = draftMinTokens,
                                draftPMin = draftPMin,
                                parallel = null,
                                cacheRam = null,
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
                                            
                                            // Speculative
                                            settingsRepo.setSpeculativeEnabled(cmd.speculativeEnabled)
                                            settingsRepo.setDraftModelPath(cmd.draftModelPath)
                                            settingsRepo.setDraftMaxTokens(cmd.draftMax)
                                            settingsRepo.setDraftMinTokens(cmd.draftMin)
                                            settingsRepo.setDraftPMin(cmd.draftPMin)
                                            
                                            // Advanced & Vision
                                            settingsRepo.setCustomCommandTemplate(cmd.commandTemplate)
                                            settingsRepo.setCustomFlags(cmd.customFlags)
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
                                        Text("Model: ${cmd.modelPath.substringAfterLast("/")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            shape = RoundedCornerShape(20.dp)
        )
    }
}
