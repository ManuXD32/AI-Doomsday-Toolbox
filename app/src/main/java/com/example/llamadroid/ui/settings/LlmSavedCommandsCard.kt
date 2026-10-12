package com.example.llamadroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.SavedCommand
import com.example.llamadroid.data.db.SavedCommandScopes
import com.example.llamadroid.data.db.launchProfile
import com.example.llamadroid.data.db.savedCommandFromLaunchProfile
import com.example.llamadroid.service.LlamaServerLaunchProfile
import com.example.llamadroid.service.LlamaSpeculativeMode
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog
import kotlinx.coroutines.launch

private typealias SavedCommandEntity = SavedCommand

/**
 * Global-only "Saved commands" card (Load / Save) with its dialogs. It is hidden in the per-server
 * editor, where the draft profile is saved with the server instead.
 */
@Composable
internal fun LlmSavedCommandsCard(settingsRepo: SettingsRepository, effectiveDraftModelPath: String?) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val db = remember { AppDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    val selectedModelPath by settingsRepo.selectedModelPath.collectAsState()
    val speculativeEnabled by settingsRepo.speculativeEnabled.collectAsState()
    val speculativeMode by settingsRepo.speculativeMode.collectAsState()
    val mtpUseDraftModel by settingsRepo.mtpUseDraftModel.collectAsState()
    val savedCommands by db.savedCommandDao()
        .getCommandsByScope(SavedCommandScopes.GENERAL)
        .collectAsState(initial = emptyList())
    var showSaveCommandDialog by remember { mutableStateOf(false) }
    var saveCommandName by remember { mutableStateOf("") }
    var selectedSaveCommandId by remember { mutableStateOf<Long?>(null) }
    var showOverwriteSavedCommandDialog by remember { mutableStateOf(false) }
    var showLoadCommandDialog by remember { mutableStateOf(false) }
    var showCommandPreview by remember { mutableStateOf<SavedCommandEntity?>(null) }

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

    // Shared with the server manager; edits remain attached to the same saved profile.
    showCommandPreview?.let { cmd ->
        SavedLlamaCommandEditor(command = cmd, onDismiss = { showCommandPreview = null }) { name, profile ->
            scope.launch {
                db.savedCommandDao().insertCommand(savedCommandFromLaunchProfile(name, profile, id = cmd.id))
            }
            android.widget.Toast.makeText(context, resources.getString(R.string.saved_command_overwritten),
                android.widget.Toast.LENGTH_SHORT).show()
            showCommandPreview = null
        }
    }
}
