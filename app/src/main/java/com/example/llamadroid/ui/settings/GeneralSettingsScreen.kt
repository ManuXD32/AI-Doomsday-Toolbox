package com.example.llamadroid.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
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
import com.example.llamadroid.data.backup.NativeChatNotesBackupManager
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.DatabaseBackupManager
import com.example.llamadroid.quadtrix.QuadtrixWorkspaceManager
import com.example.llamadroid.ui.components.AppScreenScaffold
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
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }
    
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
                Toast.makeText(context, context.getString(R.string.quadtrix_workspace_ready), Toast.LENGTH_LONG).show()
                if (selection.directPath.isNullOrBlank()) {
                    Toast.makeText(context, context.getString(R.string.quadtrix_workspace_direct_required), Toast.LENGTH_LONG).show()
                }
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    context.getString(R.string.quadtrix_workspace_setup_failed, error.message ?: context.getString(R.string.error_generic)),
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
                val llmAccelerationMode by settingsRepo.llmAccelerationMode.collectAsState()
                val llmOptions = listOf(
                    SettingsRepository.ACCELERATION_AUTO to stringResource(R.string.general_acceleration_mode_auto),
                    SettingsRepository.ACCELERATION_CPU to stringResource(R.string.general_acceleration_mode_cpu),
                    SettingsRepository.ACCELERATION_GPU to stringResource(R.string.general_acceleration_mode_gpu_opencl)
                )

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

                        AccelerationModeSelector(
                            label = stringResource(R.string.general_acceleration_llm),
                            selected = llmAccelerationMode,
                            options = llmOptions,
                            onSelected = settingsRepo::setLlmAccelerationMode
                        )
                        Text(
                            stringResource(R.string.general_acceleration_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
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
                                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = android.net.Uri.parse("package:$packageName")
                                    }
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
                val scope = rememberCoroutineScope()
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
                                Toast.makeText(context, context.getString(R.string.backup_success), Toast.LENGTH_LONG).show()
                            }.onFailure { e ->
                                Toast.makeText(context, context.getString(R.string.backup_error, e.message), Toast.LENGTH_LONG).show()
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
                                    context.getString(
                                        R.string.llama_backup_export_success,
                                        stats.chats,
                                        stats.notes,
                                        stats.organizerEvents,
                                        stats.organizerAlarms,
                                        stats.mediaFiles,
                                        stats.models
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.llama_backup_export_failed,
                                        error.message ?: context.getString(R.string.error_generic)
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
                                            Toast.makeText(context, context.getString(R.string.backup_restore_success), Toast.LENGTH_LONG).show()
                                            // Restart the app so Room picks up the new DB files
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                val pm = context.packageManager
                                                val intent = pm.getLaunchIntentForPackage(context.packageName)
                                                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                                Runtime.getRuntime().exit(0)
                                            }, 1500)
                                        }.onFailure { e ->
                                            Toast.makeText(context, context.getString(R.string.backup_restore_error, e.message), Toast.LENGTH_LONG).show()
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
                                                context.getString(
                                                    R.string.llama_backup_import_success,
                                                    stats.chats,
                                                    stats.notes,
                                                    stats.organizerEvents,
                                                    stats.organizerAlarms,
                                                    stats.mediaFiles,
                                                    stats.models
                                                ),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }.onFailure { error ->
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.llama_backup_import_failed,
                                                    error.message ?: context.getString(R.string.error_generic)
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
            
            // All Files Access (for direct SD card model access)
            item {
                var hasAllFilesAccess by remember { 
                    mutableStateOf(com.example.llamadroid.util.StoragePermissionHelper.hasAllFilesAccess()) 
                }
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (hasAllFilesAccess) 
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else 
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📁", style = MaterialTheme.typography.headlineSmall)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.general_all_files_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (hasAllFilesAccess) 
                                stringResource(R.string.general_all_files_enabled)
                            else 
                                stringResource(R.string.general_all_files_disabled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (!hasAllFilesAccess) {
                            Button(
                                onClick = {
                                    com.example.llamadroid.util.StoragePermissionHelper.requestAllFilesAccess(context)
                                    // Re-check after a delay
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        hasAllFilesAccess = com.example.llamadroid.util.StoragePermissionHelper.hasAllFilesAccess()
                                    }, 1000)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.general_all_files_grant_btn))
                            }
                            Text(
                                stringResource(R.string.general_all_files_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        } else {
                            Text(
                                stringResource(R.string.general_all_files_ok),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccelerationModeSelector(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: options.first().second

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
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelected(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
