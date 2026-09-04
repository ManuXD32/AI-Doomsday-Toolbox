package com.example.llamadroid.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.backup.NativeChatNotesBackupManager
import com.example.llamadroid.data.binary.BinaryAvailability
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DatabaseBackupManager
import com.example.llamadroid.quadtrix.QuadtrixWorkspaceManager
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.util.AccelerationWorkload
import com.example.llamadroid.util.CpuFeatures
import com.example.llamadroid.util.CustomBinaryFamily
import com.example.llamadroid.util.CustomBinaryPackage
import com.example.llamadroid.util.CustomBinaryPackageManager
import com.example.llamadroid.util.DeviceAcceleration
import com.example.llamadroid.util.DynamicFeatureManager
import com.example.llamadroid.util.NativeFeatureModuleManager
import com.example.llamadroid.util.NativeModuleDelivery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * General Settings - Output folder, Theme, Language
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }
    val nativeModuleManager = remember { NativeFeatureModuleManager(context) }
    val nativeModuleStates by nativeModuleManager.states.collectAsState()
    val customBinaryManager = remember { CustomBinaryPackageManager(context) }
    var customBinaryPackages by remember { mutableStateOf(customBinaryManager.listPackages()) }
    var showBinaryCatalog by remember { mutableStateOf(false) }
    DisposableEffect(nativeModuleManager) { onDispose { nativeModuleManager.close() } }
    
    val outputFolderUri by settingsRepo.outputFolderUri.collectAsState()
    val quadtrixWorkspaceUri by settingsRepo.quadtrixWorkspaceUri.collectAsState()
    val quadtrixWorkspacePath by settingsRepo.quadtrixWorkspacePath.collectAsState()
    
    // Folder picker
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            settingsRepo.setOutputFolderUri(it.toString())
        }
    }

    val quadtrixWorkspacePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                QuadtrixWorkspaceManager.configureWorkspace(context, uri)
            }
            result.onSuccess { selection ->
                settingsRepo.setQuadtrixWorkspace(selection.uri, selection.directPath)
                Toast.makeText(context, resources.getString(R.string.quadtrix_workspace_ready), Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    resources.getString(R.string.quadtrix_workspace_setup_failed, error.message ?: resources.getString(R.string.error_generic)),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val customBinaryZipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            customBinaryManager.importZip(uri)
                .onSuccess { imported ->
                    customBinaryPackages = customBinaryManager.listPackages()
                    Toast.makeText(
                        context,
                        resources.getString(R.string.binary_catalog_custom_imported, imported.name),
                        Toast.LENGTH_LONG
                    ).show()
                }
                .onFailure { error ->
                    Toast.makeText(
                        context,
                        resources.getString(
                            R.string.binary_catalog_custom_import_failed,
                            error.message ?: resources.getString(R.string.error_generic)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }
    
    AppScreenScaffold(
        title = stringResource(R.string.general_settings_title),
        subtitle = stringResource(R.string.settings_subtitle),
        onBack = { navController.popBackStack() }
    ) { _ ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Output Folder
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📂", style = MaterialTheme.typography.headlineSmall)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.general_output_folder),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            outputFolderUri ?: stringResource(R.string.general_output_default),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { folderPicker.launch(null) }) {
                                Text(stringResource(R.string.action_change))
                            }
                            if (outputFolderUri != null) {
                                TextButton(onClick = { settingsRepo.setOutputFolderUri(null) }) {
                                    Text(stringResource(R.string.action_reset))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.general_output_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🧠", style = MaterialTheme.typography.headlineSmall)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.general_quadtrix_workspace_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(R.string.general_quadtrix_workspace_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            quadtrixWorkspacePath ?: quadtrixWorkspaceUri ?: stringResource(R.string.quadtrix_workspace_not_set),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { quadtrixWorkspacePicker.launch(null) }) {
                                Text(stringResource(if (quadtrixWorkspaceUri == null) R.string.quadtrix_workspace_choose else R.string.quadtrix_workspace_change))
                            }
                            if (quadtrixWorkspaceUri != null || quadtrixWorkspacePath != null) {
                                TextButton(onClick = { settingsRepo.setQuadtrixWorkspace(null, null) }) {
                                    Text(stringResource(R.string.action_reset))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.general_quadtrix_workspace_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            
            // Theme (placeholder for future)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🎨", style = MaterialTheme.typography.headlineSmall)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.general_theme),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.general_theme_system),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.general_theme_soon),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            
            // Language Selection
            item {
                val selectedLanguage by settingsRepo.selectedLanguage.collectAsState()
                var expanded by remember { mutableStateOf(false) }
                
                val languages = listOf(
                    "system" to stringResource(R.string.general_language_system),
                    "en" to stringResource(R.string.general_language_en),
                    "es" to stringResource(R.string.general_language_es)
                )
                val currentLanguageName = languages.find { it.first == selectedLanguage }?.second ?: stringResource(R.string.general_language_system)
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🌐", style = MaterialTheme.typography.headlineSmall)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.general_language),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Box {
                            OutlinedButton(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(currentLanguageName)
                                Spacer(modifier = Modifier.weight(1f))
                                Text("▼")
                            }
                            
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                languages.forEach { (code, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            settingsRepo.setSelectedLanguage(code)
                                            expanded = false
                                            // Restart the app to apply the new locale
                                            val activity = context as? android.app.Activity
                                            if (activity != null) {
                                                val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                                                intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                activity.finishAffinity()
                                                activity.startActivity(intent)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.general_language_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            
            item {
                val llmBinarySelection by settingsRepo.llmNativeBinarySelection.collectAsState()
                val sdBinarySelection by settingsRepo.stableDiffusionNativeBinarySelection.collectAsState()
                val optionalModuleStates by DynamicFeatureManager.optionalModuleStates.collectAsState()
                val runtimeFailures by DeviceAcceleration.runtimeFailures.collectAsState()
                val binaryRepo = remember { BinaryRepository(context) }
                val isSnapdragonCompatible = remember { DeviceAcceleration.isSnapdragonCompatible() }
                val deviceCpuTier = remember {
                    runCatching { CpuFeatures.getTier() }.getOrDefault("baseline")
                }
                val optionalModuleRefresh = optionalModuleStates.hashCode()
                val llmBaselineAvailability = optionalModuleRefresh.let {
                    binaryRepo.llamaCpuTierAvailability(BinaryRepository.TIER_BASELINE)
                }
                val llmDotprodAvailability = optionalModuleRefresh.let {
                    binaryRepo.llamaCpuTierAvailability(BinaryRepository.TIER_DOTPROD)
                }
                val llmArmv9Availability = optionalModuleRefresh.let {
                    binaryRepo.llamaCpuTierAvailability(BinaryRepository.TIER_ARMV9)
                }
                val llmI8mmAvailability = optionalModuleRefresh.let {
                    binaryRepo.llamaCpuTierAvailability(BinaryRepository.TIER_I8MM)
                }
                val sdI8mmAvailable = optionalModuleRefresh.let {
                    runCatching { CpuFeatures.hasI8mm() }.getOrDefault(false)
                }
                val llmAcceleratorInstalled = optionalModuleRefresh.let {
                    DynamicFeatureManager.isModuleInstalled(
                        context,
                        DeviceAcceleration.MODULE_LLM_SNAPDRAGON_OPENCL
                    )
                }
                val sdAcceleratorInstalled = optionalModuleRefresh.let {
                    DeviceAcceleration.stableDiffusionSnapdragonModules.any {
                        DynamicFeatureManager.isModuleInstalled(context, it)
                    }
                }
                val needsGpuInstall = isSnapdragonCompatible && (!llmAcceleratorInstalled || !sdAcceleratorInstalled)
                val llmAcceleratorMessage = nativeBinaryAcceleratorMessage(
                    isSnapdragonCompatible = isSnapdragonCompatible,
                    isInstalled = llmAcceleratorInstalled,
                    runtimeFailure = runtimeFailures[AccelerationWorkload.LLM]
                )
                val sdAcceleratorMessage = nativeBinaryAcceleratorMessage(
                    isSnapdragonCompatible = isSnapdragonCompatible,
                    isInstalled = sdAcceleratorInstalled,
                    runtimeFailure = runtimeFailures[AccelerationWorkload.STABLE_DIFFUSION]
                )
                val normalizedLlmSelection = SettingsRepository.normalizeLlmNativeBinarySelection(llmBinarySelection)
                val normalizedSdSelection = SettingsRepository.normalizeStableDiffusionNativeBinarySelection(sdBinarySelection)
                val llmResolution = remember(normalizedLlmSelection, optionalModuleRefresh) {
                    binaryRepo.resolveCurrentLlamaBinary(normalizedLlmSelection)
                }
                val selectedLlmCpuTier = BinaryRepository.exactCpuTierForNativeSelection(normalizedLlmSelection)
                val selectedLlmCpuAvailability = when (selectedLlmCpuTier) {
                    BinaryRepository.TIER_BASELINE -> llmBaselineAvailability
                    BinaryRepository.TIER_DOTPROD -> llmDotprodAvailability
                    BinaryRepository.TIER_ARMV9 -> llmArmv9Availability
                    BinaryRepository.TIER_I8MM -> llmI8mmAvailability
                    else -> null
                }
                val selectedLlmCpuModule = selectedLlmCpuTier?.let(DynamicFeatureManager::getLlmCpuModuleForTier)
                val selectedModuleState = selectedLlmCpuModule?.let { optionalModuleStates[it] }
                val canInstallSelectedLlmCpuModule =
                    selectedLlmCpuModule != null &&
                        selectedLlmCpuAvailability?.hardwareCompatible == true &&
                        selectedLlmCpuAvailability?.installed == false
                val selectedLlmCpuModuleIncomplete =
                    selectedLlmCpuAvailability?.installed == true &&
                        selectedLlmCpuAvailability?.complete == false
                var pendingExperimentalSelection by remember { mutableStateOf<String?>(null) }
                var pendingExperimentalSelectionIsLlm by remember { mutableStateOf(false) }

                fun requestSelectedLlmCpuModuleIfMissing(selection: String) {
                    val tier = BinaryRepository.exactCpuTierForNativeSelection(selection) ?: return
                    val availability = when (tier) {
                        BinaryRepository.TIER_BASELINE -> llmBaselineAvailability
                        BinaryRepository.TIER_DOTPROD -> llmDotprodAvailability
                        BinaryRepository.TIER_ARMV9 -> llmArmv9Availability
                        BinaryRepository.TIER_I8MM -> llmI8mmAvailability
                        else -> null
                    } ?: return
                    if (!availability.hardwareCompatible || availability.installed) return
                    DynamicFeatureManager.getLlmCpuModuleForTier(tier)?.let { module ->
                        DynamicFeatureManager.installModule(context, module)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⚙", style = MaterialTheme.typography.headlineSmall)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.general_acceleration_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(R.string.general_acceleration_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        NativeBinarySelector(
                            label = stringResource(R.string.general_acceleration_llm),
                            selected = normalizedLlmSelection,
                            options = llmNativeBinaryOptions(
                                baselineAvailability = llmBaselineAvailability,
                                dotprodAvailability = llmDotprodAvailability,
                                armv9Availability = llmArmv9Availability,
                                i8mmAvailability = llmI8mmAvailability,
                                automaticDetail = automaticLlmDetail(llmResolution, binaryBaseName = "llama_server"),
                                binaryBaseName = "llama_server",
                                acceleratorEnabled = isSnapdragonCompatible && llmAcceleratorInstalled,
                                customPackages = customBinaryPackages
                            ),
                            onSelected = { selection ->
                                if (isExperimentalNativeBinarySelection(selection)) {
                                    pendingExperimentalSelection = selection
                                    pendingExperimentalSelectionIsLlm = true
                                } else {
                                    settingsRepo.setLlmNativeBinarySelection(selection)
                                    requestSelectedLlmCpuModuleIfMissing(selection)
                                }
                            }
                        )
                        val installableLlmCpuModule = selectedLlmCpuModule?.takeIf { canInstallSelectedLlmCpuModule }
                        if (installableLlmCpuModule != null) {
                            OutlinedButton(
                                onClick = { DynamicFeatureManager.installModule(context, installableLlmCpuModule) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val progress = selectedModuleState?.progress
                                Text(
                                    if (selectedModuleState?.status == com.example.llamadroid.util.AccelerationStatus.INSTALLING &&
                                        progress != null
                                    ) {
                                        stringResource(R.string.general_native_binary_installing_cpu, progress)
                                    } else {
                                        stringResource(R.string.general_native_binary_install_cpu)
                                    }
                                )
                            }
                        }
                        if (selectedLlmCpuModuleIncomplete) {
                            Text(
                                stringResource(R.string.general_native_binary_module_incomplete),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (llmI8mmAvailability.quarantined) {
                            OutlinedButton(
                                onClick = {
                                    binaryRepo.clearI8mmQuarantineForCurrentVersion()
                                    settingsRepo.setLlmNativeBinarySelection(SettingsRepository.NATIVE_BINARY_CPU_I8MM)
                                    nativeModuleManager.refresh()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.general_native_binary_retry_i8mm)) }
                        }
                        llmAcceleratorMessage?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }

                        NativeBinarySelector(
                            label = stringResource(R.string.general_acceleration_stable_diffusion),
                            selected = normalizedSdSelection,
                            options = sdNativeBinaryOptions(
                                deviceCpuTier = deviceCpuTier,
                                binaryBaseName = "sd",
                                i8mmEnabled = sdI8mmAvailable,
                                acceleratorEnabled = isSnapdragonCompatible && sdAcceleratorInstalled,
                                customPackages = customBinaryPackages
                            ),
                            onSelected = { selection ->
                                if (isExperimentalNativeBinarySelection(selection)) {
                                    pendingExperimentalSelection = selection
                                    pendingExperimentalSelectionIsLlm = false
                                } else {
                                    settingsRepo.setStableDiffusionNativeBinarySelection(selection)
                                    if (selection == SettingsRepository.NATIVE_BINARY_CPU_I8MM) {
                                        DynamicFeatureManager.installModule(context, DynamicFeatureManager.MODULE_MEDIA_I8MM)
                                    }
                                }
                            }
                        )
                        sdAcceleratorMessage?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }
                        if (needsGpuInstall) {
                            OutlinedButton(
                                onClick = { DynamicFeatureManager.installOptionalAccelerators(context) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.general_native_binary_install_gpu))
                            }
                        }
                        Text(
                            stringResource(R.string.general_acceleration_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showBinaryCatalog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(stringResource(R.string.binary_catalog_title), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(
                                R.string.binary_catalog_summary,
                                nativeModuleStates.count {
                                    it.lifecycle == com.example.llamadroid.util.NativeModuleLifecycle.INSTALLED
                                },
                                nativeModuleStates.count { it.compatible }
                            ),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                pendingExperimentalSelection?.let { pending ->
                    AlertDialog(
                        onDismissRequest = { pendingExperimentalSelection = null },
                        title = { Text(stringResource(R.string.general_native_binary_experimental_warning_title)) },
                        text = { Text(stringResource(R.string.general_native_binary_experimental_warning_body)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (pendingExperimentalSelectionIsLlm) {
                                        settingsRepo.setLlmNativeBinarySelection(pending)
                                    } else {
                                        settingsRepo.setStableDiffusionNativeBinarySelection(pending)
                                    }
                                    pendingExperimentalSelection = null
                                }
                            ) {
                                Text(stringResource(R.string.general_native_binary_experimental_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingExperimentalSelection = null }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }
            }

            // Battery Optimization
            item {
                val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                val packageName = context.packageName
                var isIgnoringBatteryOptimizations by remember { 
                    mutableStateOf(powerManager.isIgnoringBatteryOptimizations(packageName)) 
                }
                val keepScreenAwakeDuringGeneration by settingsRepo.keepScreenAwakeDuringGeneration.collectAsState()
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isIgnoringBatteryOptimizations) 
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else 
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔋", style = MaterialTheme.typography.headlineSmall)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.general_battery),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (isIgnoringBatteryOptimizations) 
                                stringResource(R.string.general_battery_unrestricted)
                            else 
                                stringResource(R.string.general_battery_restricted),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (!isIgnoringBatteryOptimizations) {
                            Button(
                                onClick = {
                                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                    // Re-check after a delay (user might grant immediately)
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)
                                    }, 1000)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.general_battery_allow))
                            }
                            Text(
                                stringResource(R.string.general_battery_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        } else {
                            Text(
                                stringResource(R.string.general_battery_ok),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.general_battery_keep_screen_awake_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.general_battery_keep_screen_awake_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                )
                            }
                            Switch(
                                checked = keepScreenAwakeDuringGeneration,
                                onCheckedChange = settingsRepo::setKeepScreenAwakeDuringGeneration
                            )
                        }
                        
                        // Always show device-specific help
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.general_battery_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://dontkillmyapp.com"))
                                context.startActivity(intent)
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                stringResource(R.string.general_battery_fix_link),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // Backups
            item {
                val database = remember { AppDatabase.getDatabase(context) }
                var isBackingUp by remember { mutableStateOf(false) }
                var isRestoring by remember { mutableStateOf(false) }
                var isNativeBackupBusy by remember { mutableStateOf(false) }
                var showRestoreConfirm by remember { mutableStateOf(false) }
                var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
                var showNativeImportConfirm by remember { mutableStateOf(false) }
                var pendingNativeImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

                // SAF file creator for backup
                val backupFilePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/zip")
                ) { uri ->
                    uri?.let {
                        isBackingUp = true
                        scope.launch {
                            val result = DatabaseBackupManager.createBackup(context, it)
                            isBackingUp = false
                            result.onSuccess {
                                Toast.makeText(context, resources.getString(R.string.backup_success), Toast.LENGTH_LONG).show()
                            }.onFailure { e ->
                                Toast.makeText(context, resources.getString(R.string.backup_error, e.message), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                // SAF file picker for restore
                val restoreFilePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let {
                        pendingRestoreUri = it
                        showRestoreConfirm = true
                    }
                }

                val nativeBackupExportPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/zip")
                ) { uri ->
                    uri?.let {
                        isNativeBackupBusy = true
                        scope.launch {
                            val result = NativeChatNotesBackupManager.exportToZip(context, database, it)
                            isNativeBackupBusy = false
                            result.onSuccess { stats ->
                                Toast.makeText(
                                    context,
                                    resources.getString(
                                        R.string.llama_backup_export_success,
                                        stats.chats,
                                        stats.notes,
                                        stats.organizerEvents,
                                        stats.organizerAlarms,
                                        stats.mediaFiles
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    resources.getString(
                                        R.string.llama_backup_export_failed,
                                        error.message ?: resources.getString(R.string.error_generic)
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }

                val nativeBackupImportPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let {
                        pendingNativeImportUri = it
                        showNativeImportConfirm = true
                    }
                }

                // Restore confirmation dialog
                if (showRestoreConfirm && pendingRestoreUri != null) {
                    AlertDialog(
                        onDismissRequest = { showRestoreConfirm = false; pendingRestoreUri = null },
                        title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
                        text = { Text(stringResource(R.string.backup_restore_confirm_msg)) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showRestoreConfirm = false
                                    val uri = pendingRestoreUri ?: return@Button
                                    pendingRestoreUri = null
                                    isRestoring = true
                                    scope.launch {
                                        val result = DatabaseBackupManager.restoreBackup(context, uri)
                                        isRestoring = false
                                        result.onSuccess {
                                            Toast.makeText(context, resources.getString(R.string.backup_restore_success), Toast.LENGTH_LONG).show()
                                            // Restart the app so Room picks up the new DB files
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                val pm = context.packageManager
                                                val intent = pm.getLaunchIntentForPackage(context.packageName)
                                                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                Runtime.getRuntime().exit(0)
                                            }, 1500)
                                        }.onFailure { e ->
                                            Toast.makeText(context, resources.getString(R.string.backup_restore_error, e.message), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(stringResource(R.string.backup_restore_confirm_btn))
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { showRestoreConfirm = false; pendingRestoreUri = null }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }

                if (showNativeImportConfirm && pendingNativeImportUri != null) {
                    AlertDialog(
                        onDismissRequest = {
                            showNativeImportConfirm = false
                            pendingNativeImportUri = null
                        },
                        title = { Text(stringResource(R.string.llama_backup_import_confirm_title)) },
                        text = { Text(stringResource(R.string.llama_backup_import_confirm_message)) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val uri = pendingNativeImportUri ?: return@Button
                                    showNativeImportConfirm = false
                                    pendingNativeImportUri = null
                                    isNativeBackupBusy = true
                                    scope.launch {
                                        val result = NativeChatNotesBackupManager.importFromZip(context, database, uri)
                                        isNativeBackupBusy = false
                                        result.onSuccess { stats ->
                                            Toast.makeText(
                                                context,
                                                resources.getString(
                                                    R.string.llama_backup_import_success,
                                                    stats.chats,
                                                    stats.notes,
                                                    stats.organizerEvents,
                                                    stats.organizerAlarms,
                                                    stats.mediaFiles
                                                ),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }.onFailure { error ->
                                            Toast.makeText(
                                                context,
                                                resources.getString(
                                                    R.string.llama_backup_import_failed,
                                                    error.message ?: resources.getString(R.string.error_generic)
                                                ),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.action_import))
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = {
                                    showNativeImportConfirm = false
                                    pendingNativeImportUri = null
                                }
                            ) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("💾", style = MaterialTheme.typography.headlineSmall)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    stringResource(R.string.backup_section_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(R.string.backup_section_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.backup_database_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.backup_database_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    backupFilePicker.launch(DatabaseBackupManager.generateBackupFilename())
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isBackingUp && !isRestoring
                            ) {
                                if (isBackingUp) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(if (isBackingUp) stringResource(R.string.backup_creating) else stringResource(R.string.backup_create_btn))
                            }
                            OutlinedButton(
                                onClick = {
                                    restoreFilePicker.launch(arrayOf("application/zip"))
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isBackingUp && !isRestoring
                            ) {
                                if (isRestoring) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(if (isRestoring) stringResource(R.string.backup_restoring) else stringResource(R.string.backup_restore_btn))
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.backup_native_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.backup_native_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    nativeBackupExportPicker.launch(NativeChatNotesBackupManager.generateBackupFilename())
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isNativeBackupBusy
                            ) {
                                if (isNativeBackupBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.backup_native_export_btn))
                            }
                            OutlinedButton(
                                onClick = {
                                    nativeBackupImportPicker.launch(
                                        arrayOf(
                                            "application/zip",
                                            "application/octet-stream",
                                            "application/x-zip-compressed"
                                        )
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isNativeBackupBusy
                            ) {
                                Text(stringResource(R.string.backup_native_import_btn))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.backup_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            
        }
    }
    if (showBinaryCatalog) {
        NativeBinaryCatalogSheet(
            states = nativeModuleStates,
            customPackages = customBinaryPackages,
            onDismiss = { showBinaryCatalog = false },
            onInstall = nativeModuleManager::requestInstall,
            onRemove = nativeModuleManager::requestRemoval,
            onImportCustom = {
                customBinaryZipPicker.launch(
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                )
            },
            onRemoveCustom = { binaryPackage ->
                scope.launch {
                    customBinaryManager.remove(binaryPackage.id).onSuccess {
                        if (SettingsRepository.normalizeLlmNativeBinarySelection(settingsRepo.llmNativeBinarySelection.value) ==
                            binaryPackage.selectionValue
                        ) {
                            settingsRepo.setLlmNativeBinarySelection(SettingsRepository.NATIVE_BINARY_AUTO)
                        }
                        if (SettingsRepository.normalizeStableDiffusionNativeBinarySelection(
                                settingsRepo.stableDiffusionNativeBinarySelection.value
                            ) == binaryPackage.selectionValue
                        ) {
                            settingsRepo.setStableDiffusionNativeBinarySelection(SettingsRepository.NATIVE_BINARY_AUTO)
                        }
                        customBinaryPackages = customBinaryManager.listPackages()
                    }
                }
            }
        )
    }
}

@Composable
private fun nativeModuleDisplayName(moduleName: String): String = when (moduleName) {
    "feature_media_i8mm" -> stringResource(R.string.title_feature_media_i8mm)
    "feature_media_snapdragon_opencl" -> stringResource(R.string.title_feature_media_snapdragon_opencl)
    else -> moduleName.removePrefix("feature_").replace('_', ' ')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeBinaryCatalogSheet(
    states: List<com.example.llamadroid.util.NativeModuleState>,
    customPackages: List<CustomBinaryPackage>,
    onDismiss: () -> Unit,
    onInstall: (String) -> Unit,
    onRemove: (String) -> Unit,
    onImportCustom: () -> Unit,
    onRemoveCustom: (CustomBinaryPackage) -> Unit
) {
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    var pendingCustomRemoval by remember { mutableStateOf<CustomBinaryPackage?>(null) }
    var showCustomPackageHelp by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                stringResource(R.string.binary_catalog_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.native_modules_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.binary_catalog_llm_section),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                val llmStates = states.filter { it.definition.family == com.example.llamadroid.util.NativeEngineFamily.LLM }
                items(llmStates.size, key = { llmStates[it].definition.moduleName }) { index ->
                    val state = llmStates[index]
                    val module = state.definition.moduleName
                    val title = nativeModuleDisplayName(module)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        state.delivery != NativeModuleDelivery.PLAY_MANAGED &&
                                            state.lifecycle == com.example.llamadroid.util.NativeModuleLifecycle.INSTALLED ->
                                            stringResource(R.string.native_modules_included)
                                        state.lifecycle == com.example.llamadroid.util.NativeModuleLifecycle.INSTALLED ->
                                            stringResource(R.string.native_modules_installed)
                                        !state.compatible -> stringResource(R.string.native_modules_incompatible)
                                        else -> stringResource(R.string.native_modules_not_installed)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.delivery == NativeModuleDelivery.PLAY_MANAGED && state.compatible) {
                                TextButton(
                                    onClick = {
                                        if (state.lifecycle == com.example.llamadroid.util.NativeModuleLifecycle.INSTALLED) {
                                            pendingRemoval = module
                                        } else {
                                            onInstall(module)
                                        }
                                    }
                                ) {
                                    Text(
                                        stringResource(
                                            if (state.lifecycle == com.example.llamadroid.util.NativeModuleLifecycle.INSTALLED)
                                                R.string.native_modules_remove
                                            else R.string.native_modules_download
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.binary_catalog_sd_section),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                val mediaStates = states.filter { it.definition.family == com.example.llamadroid.util.NativeEngineFamily.MEDIA }
                items(mediaStates.size, key = { mediaStates[it].definition.moduleName }) { index ->
                    val state = mediaStates[index]
                    val module = state.definition.moduleName
                    val title = nativeModuleDisplayName(module)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        state.delivery != NativeModuleDelivery.PLAY_MANAGED &&
                                            state.lifecycle == com.example.llamadroid.util.NativeModuleLifecycle.INSTALLED ->
                                            stringResource(R.string.native_modules_included)
                                        state.lifecycle == com.example.llamadroid.util.NativeModuleLifecycle.INSTALLED ->
                                            stringResource(R.string.native_modules_installed)
                                        !state.compatible -> stringResource(R.string.native_modules_incompatible)
                                        else -> stringResource(R.string.native_modules_not_installed)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.delivery == NativeModuleDelivery.PLAY_MANAGED && state.compatible) {
                                TextButton(onClick = {
                                    if (state.lifecycle == com.example.llamadroid.util.NativeModuleLifecycle.INSTALLED) {
                                        pendingRemoval = module
                                    } else {
                                        onInstall(module)
                                    }
                                }) {
                                    Text(
                                        stringResource(
                                            if (state.lifecycle == com.example.llamadroid.util.NativeModuleLifecycle.INSTALLED)
                                                R.string.native_modules_remove
                                            else R.string.native_modules_download
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.binary_catalog_custom_section),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showCustomPackageHelp = true }) {
                            Icon(Icons.Default.Settings, stringResource(R.string.binary_catalog_custom_info))
                        }
                    }
                }
                if (customPackages.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.binary_catalog_custom_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(customPackages.size, key = { customPackages[it].id }) { index ->
                        val binaryPackage = customPackages[index]
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(binaryPackage.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        stringResource(
                                            if (binaryPackage.family == CustomBinaryFamily.LLM_SERVER)
                                                R.string.binary_catalog_custom_llm
                                            else R.string.binary_catalog_custom_sd,
                                            binaryPackage.version,
                                            binaryPackage.installedBytes / (1024L * 1024L)
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { pendingCustomRemoval = binaryPackage }) {
                                    Text(stringResource(R.string.native_modules_remove))
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = onImportCustom, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.binary_catalog_custom_import))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    pendingRemoval?.let { module ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.binary_catalog_remove_title)) },
            text = { Text(stringResource(R.string.binary_catalog_remove_message, nativeModuleDisplayName(module))) },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(module)
                    pendingRemoval = null
                }) { Text(stringResource(R.string.native_modules_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    pendingCustomRemoval?.let { binaryPackage ->
        AlertDialog(
            onDismissRequest = { pendingCustomRemoval = null },
            title = { Text(stringResource(R.string.binary_catalog_remove_title)) },
            text = { Text(stringResource(R.string.binary_catalog_remove_message, binaryPackage.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveCustom(binaryPackage)
                    pendingCustomRemoval = null
                }) { Text(stringResource(R.string.native_modules_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCustomRemoval = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (showCustomPackageHelp) {
        AlertDialog(
            onDismissRequest = { showCustomPackageHelp = false },
            title = { Text(stringResource(R.string.binary_catalog_custom_info_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.binary_catalog_custom_info_body))
                    SelectionContainer {
                        Text(
                            stringResource(R.string.binary_catalog_custom_example),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCustomPackageHelp = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
}

private data class NativeBinaryOption(
    val value: String,
    val label: String,
    val detail: String? = null,
    val enabled: Boolean = true
)

internal fun isExperimentalNativeBinarySelection(selection: String): Boolean =
    selection == SettingsRepository.NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL ||
        selection == SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_VULKAN ||
        selection == SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_OPENCL

private fun nativeBinarySelectionForCpuTier(tier: String): String =
    when (tier) {
        "armv9" -> SettingsRepository.NATIVE_BINARY_CPU_ARMV9
        "dotprod" -> SettingsRepository.NATIVE_BINARY_CPU_DOTPROD
        else -> SettingsRepository.NATIVE_BINARY_CPU_BASELINE
    }

private fun isConcreteCpuNativeBinarySelection(selection: String): Boolean =
    selection == SettingsRepository.NATIVE_BINARY_CPU_AUTO ||
        selection == SettingsRepository.NATIVE_BINARY_CPU_BASELINE ||
        selection == SettingsRepository.NATIVE_BINARY_CPU_DOTPROD ||
        selection == SettingsRepository.NATIVE_BINARY_CPU_ARMV9 ||
        selection == SettingsRepository.NATIVE_BINARY_CPU_I8MM

@Composable
private fun cpuNativeBinaryOption(
    value: String,
    label: String,
    availability: BinaryAvailability
): NativeBinaryOption {
    val detail = when {
        !availability.hardwareCompatible -> stringResource(R.string.general_native_binary_cpu_incompatible)
        !availability.abiCompatible -> stringResource(R.string.general_native_binary_unavailable)
        !availability.installed -> stringResource(R.string.general_native_binary_not_included)
        !availability.complete -> stringResource(R.string.general_native_binary_module_incomplete_short)
        availability.quarantined -> stringResource(R.string.general_native_binary_unavailable)
        availability.usable -> stringResource(R.string.general_native_binary_available_optimized)
        else -> stringResource(R.string.general_native_binary_not_included)
    }
    return NativeBinaryOption(
        value = value,
        label = label,
        detail = detail,
        enabled = availability.hardwareCompatible && availability.abiCompatible
    )
}

@Composable
private fun cpuNativeBinaryOption(
    value: String,
    label: String,
    compatible: Boolean
): NativeBinaryOption =
    NativeBinaryOption(
        value,
        label,
        detail = if (compatible) {
            stringResource(R.string.general_native_binary_available_optimized)
        } else {
            stringResource(R.string.general_native_binary_cpu_incompatible)
        },
        enabled = compatible
    )

private fun cpuTierCompatible(deviceCpuTier: String, selection: String): Boolean =
    when (selection) {
        SettingsRepository.NATIVE_BINARY_CPU_BASELINE -> true
        SettingsRepository.NATIVE_BINARY_CPU_DOTPROD -> deviceCpuTier == "dotprod" || deviceCpuTier == "armv9"
        SettingsRepository.NATIVE_BINARY_CPU_ARMV9 -> deviceCpuTier == "armv9"
        else -> false
    }

@Composable
private fun automaticLlmDetail(
    resolution: com.example.llamadroid.data.binary.BinaryResolution,
    binaryBaseName: String
): String {
    val effective = when (resolution.effective) {
        com.example.llamadroid.data.binary.EffectiveLlamaBinary.CPU_I8MM ->
            stringResource(R.string.general_native_binary_cpu_i8mm, binaryBaseName)
        com.example.llamadroid.data.binary.EffectiveLlamaBinary.CPU_ARMV9 ->
            stringResource(R.string.general_native_binary_cpu_armv9, binaryBaseName)
        com.example.llamadroid.data.binary.EffectiveLlamaBinary.CPU_DOTPROD ->
            stringResource(R.string.general_native_binary_cpu_dotprod, binaryBaseName)
        com.example.llamadroid.data.binary.EffectiveLlamaBinary.CPU_BASELINE ->
            stringResource(R.string.general_native_binary_cpu_baseline, binaryBaseName)
        com.example.llamadroid.data.binary.EffectiveLlamaBinary.OPENCL ->
            stringResource(R.string.general_native_binary_llm_opencl_experimental)
        com.example.llamadroid.data.binary.EffectiveLlamaBinary.VULKAN ->
            stringResource(R.string.general_native_binary_sd_vulkan_experimental)
    }
    val current = stringResource(R.string.general_native_binary_currently_using, effective)
    val i8mmUnavailable = stringResource(R.string.general_native_binary_i8mm_not_delivered)
    return if (resolution.fallbackReason?.contains("i8mm", ignoreCase = true) == true) {
        "$current\n$i8mmUnavailable"
    } else {
        current
    }
}

@Composable
private fun llmNativeBinaryOptions(
    baselineAvailability: BinaryAvailability,
    dotprodAvailability: BinaryAvailability,
    armv9Availability: BinaryAvailability,
    i8mmAvailability: BinaryAvailability,
    automaticDetail: String,
    binaryBaseName: String,
    acceleratorEnabled: Boolean,
    customPackages: List<CustomBinaryPackage>
): List<NativeBinaryOption> = listOf(
    NativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_AUTO,
        stringResource(R.string.general_acceleration_mode_auto),
        detail = automaticDetail
    ),
    cpuNativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_CPU_I8MM,
        stringResource(R.string.general_native_binary_cpu_i8mm, binaryBaseName),
        availability = i8mmAvailability
    ),
    cpuNativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_CPU_ARMV9,
        stringResource(R.string.general_native_binary_cpu_armv9, binaryBaseName),
        availability = armv9Availability
    ),
    cpuNativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_CPU_DOTPROD,
        stringResource(R.string.general_native_binary_cpu_dotprod, binaryBaseName),
        availability = dotprodAvailability
    ),
    cpuNativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_CPU_BASELINE,
        stringResource(R.string.general_native_binary_cpu_baseline, binaryBaseName),
        availability = baselineAvailability
    ),
    NativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_LLM_SNAPDRAGON_OPENCL,
        stringResource(R.string.general_native_binary_llm_opencl_experimental),
        detail = if (acceleratorEnabled) {
            stringResource(R.string.general_native_binary_available_optimized)
        } else {
            stringResource(R.string.general_native_binary_unavailable)
        },
        enabled = acceleratorEnabled
    )
) + customPackages
    .filter { it.family == CustomBinaryFamily.LLM_SERVER }
    .map {
        NativeBinaryOption(
            it.selectionValue,
            it.name,
            detail = stringResource(R.string.binary_catalog_custom_picker_detail, it.version)
        )
    }

@Composable
private fun sdNativeBinaryOptions(
    deviceCpuTier: String,
    binaryBaseName: String,
    i8mmEnabled: Boolean,
    acceleratorEnabled: Boolean,
    customPackages: List<CustomBinaryPackage>
): List<NativeBinaryOption> = listOf(
    NativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_AUTO,
        stringResource(R.string.general_acceleration_mode_auto)
    ),
    cpuNativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_CPU_I8MM,
        stringResource(R.string.general_native_binary_cpu_i8mm, binaryBaseName),
        compatible = i8mmEnabled
    ),
    cpuNativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_CPU_ARMV9,
        stringResource(R.string.general_native_binary_cpu_armv9, binaryBaseName),
        compatible = cpuTierCompatible(deviceCpuTier, SettingsRepository.NATIVE_BINARY_CPU_ARMV9)
    ),
    cpuNativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_CPU_DOTPROD,
        stringResource(R.string.general_native_binary_cpu_dotprod, binaryBaseName),
        compatible = cpuTierCompatible(deviceCpuTier, SettingsRepository.NATIVE_BINARY_CPU_DOTPROD)
    ),
    cpuNativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_CPU_BASELINE,
        stringResource(R.string.general_native_binary_cpu_baseline, binaryBaseName),
        compatible = true
    ),
    NativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_VULKAN,
        stringResource(R.string.general_native_binary_sd_vulkan_experimental),
        detail = if (acceleratorEnabled) {
            stringResource(R.string.general_native_binary_available_optimized)
        } else {
            stringResource(R.string.general_native_binary_unavailable)
        },
        enabled = acceleratorEnabled
    ),
    NativeBinaryOption(
        SettingsRepository.NATIVE_BINARY_SD_SNAPDRAGON_OPENCL,
        stringResource(R.string.general_native_binary_sd_opencl_experimental),
        detail = if (acceleratorEnabled) {
            stringResource(R.string.general_native_binary_available_optimized)
        } else {
            stringResource(R.string.general_native_binary_unavailable)
        },
        enabled = acceleratorEnabled
    )
) + customPackages
    .filter { it.family == CustomBinaryFamily.STABLE_DIFFUSION }
    .map {
        NativeBinaryOption(
            it.selectionValue,
            it.name,
            detail = stringResource(R.string.binary_catalog_custom_picker_detail, it.version)
        )
    }

@Composable
private fun nativeBinaryAcceleratorMessage(
    isSnapdragonCompatible: Boolean,
    isInstalled: Boolean,
    runtimeFailure: String?
): String? = when {
    !isSnapdragonCompatible -> stringResource(R.string.general_native_binary_device_unsupported)
    !isInstalled -> stringResource(R.string.general_native_binary_module_missing)
    runtimeFailure != null -> stringResource(R.string.general_native_binary_previous_failure, runtimeFailure)
    else -> null
}

@Composable
private fun NativeBinarySelector(
    label: String,
    selected: String,
    options: List<NativeBinaryOption>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selected }?.label ?: options.first().label

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedLabel,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    option.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                option.detail?.let { detail ->
                                    Text(
                                        detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        enabled = option.enabled,
                        onClick = {
                            onSelected(option.value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
