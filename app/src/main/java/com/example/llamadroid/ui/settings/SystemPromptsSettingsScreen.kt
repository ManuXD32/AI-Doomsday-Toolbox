package com.example.llamadroid.ui.settings

import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog as AlertDialog

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
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.SystemPromptEntity
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import kotlinx.coroutines.launch

/**
 * System Prompts Settings - Manage prompt templates for different AI features
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptsSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val walkthroughTargets = LocalWalkthroughTargets.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    
    val savedPrompts by db.systemPromptDao().getAllPrompts().collectAsState(initial = emptyList())
    val selectedPromptId by settingsRepo.selectedPromptId.collectAsState()
    
    var showAddDialog by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf<SystemPromptEntity?>(null) }
    var newPromptName by remember { mutableStateOf("") }
    var newPromptContent by remember { mutableStateOf("") }
    var promptCategory by remember { mutableStateOf("pdf_summary") }
    
    AppScreenScaffold(
        title = stringResource(R.string.prompts_title),
        subtitle = stringResource(R.string.settings_prompts_desc),
        onBack = { navController.popBackStack() }
    ) { _ ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .walkthroughTarget("settings.settings_prompts"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // PDF Summary Section
            item {
                Text(
                    stringResource(R.string.prompts_pdf_summary_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item {
                val pdfSummaryPrompt by settingsRepo.pdfSummaryPrompt.collectAsState()
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.prompts_summary_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            pdfSummaryPrompt?.take(150)?.plus("...") ?: stringResource(R.string.prompts_summary_default),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                walkthroughTargets?.recordEvent("settings.prompts")
                                promptCategory = "pdf_summary"
                                newPromptName = "PDF Summary"
                                newPromptContent = pdfSummaryPrompt ?: ""
                                showAddDialog = true
                            }) {
                                Text(stringResource(R.string.action_edit))
                            }
                            if (pdfSummaryPrompt != null) {
                                TextButton(onClick = { settingsRepo.setPdfSummaryPrompt(null) }) {
                                    Text(stringResource(R.string.action_reset))
                                }
                            }
                        }
                    }
                }
            }
            
            item {
                val pdfUnificationPrompt by settingsRepo.pdfUnificationPrompt.collectAsState()
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.prompts_unification_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            pdfUnificationPrompt?.take(150)?.plus("...") ?: stringResource(R.string.prompts_unification_default),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                walkthroughTargets?.recordEvent("settings.prompts")
                                promptCategory = "pdf_unification"
                                newPromptName = "PDF Unification"
                                newPromptContent = pdfUnificationPrompt ?: ""
                                showAddDialog = true
                            }) {
                                Text(stringResource(R.string.action_edit))
                            }
                            if (pdfUnificationPrompt != null) {
                                TextButton(onClick = { settingsRepo.setPdfUnificationPrompt(null) }) {
                                    Text(stringResource(R.string.action_reset))
                                }
                            }
                        }
                    }
                }
            }
            
            // Saved Custom Prompts Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.prompts_saved_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        walkthroughTargets?.recordEvent("settings.prompts")
                        promptCategory = "custom"
                        newPromptName = ""
                        newPromptContent = ""
                        editingPrompt = null
                        showAddDialog = true
                    }) {
                        Icon(Icons.Default.Add, stringResource(R.string.prompts_add_prompt_desc))
                    }
                }
            }
            
            if (savedPrompts.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(R.string.prompts_no_custom), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                walkthroughTargets?.recordEvent("settings.prompts")
                                promptCategory = "custom"
                                newPromptName = ""
                                newPromptContent = ""
                                showAddDialog = true
                            }) {
                                Icon(Icons.Default.Add, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.prompts_create_btn))
                            }
                        }
                    }
                }
            } else {
                items(savedPrompts) { prompt ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(prompt.name, fontWeight = FontWeight.Bold)
                                Row {
                                    IconButton(
                                        onClick = { 
                                            walkthroughTargets?.recordEvent("settings.prompts")
                                            editingPrompt = prompt
                                            promptCategory = "custom"
                                            newPromptName = prompt.name
                                            newPromptContent = prompt.content
                                            showAddDialog = true
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Create, stringResource(R.string.action_edit), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            walkthroughTargets?.recordEvent("settings.prompts")
                                            scope.launch {
                                                db.systemPromptDao().deletePrompt(prompt)
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete, 
                                            stringResource(R.string.action_delete), 
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                prompt.content.take(80) + if (prompt.content.length > 80) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Apply buttons row
                            Text(stringResource(R.string.prompts_apply_as), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(
                                    onClick = { 
                                        settingsRepo.setSelectedPromptId(prompt.id)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (selectedPromptId == prompt.id) stringResource(R.string.prompts_selected) else stringResource(R.string.prompts_select), maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { 
                                        settingsRepo.setPdfSummaryPrompt(prompt.content)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.prompts_pdf_sum_btn), maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { 
                                        settingsRepo.setPdfUnificationPrompt(prompt.content)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.prompts_pdf_unify_btn), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Add/Edit Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { 
                showAddDialog = false
                editingPrompt = null
            },
            title = { 
                Text(
                    when {
                        editingPrompt != null -> stringResource(R.string.prompts_edit_title)
                        else -> stringResource(R.string.prompts_new_title)
                    }
                ) 
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = newPromptName,
                        onValueChange = { newPromptName = it },
                        label = { Text(stringResource(R.string.prompts_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPromptContent,
                        onValueChange = { newPromptContent = it },
                        label = { Text(stringResource(R.string.prompts_content_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 150.dp),
                        maxLines = 10
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        walkthroughTargets?.recordEvent("settings.prompts")
                        scope.launch {
                            when (promptCategory) {
                                "pdf_summary" -> {
                                    settingsRepo.setPdfSummaryPrompt(newPromptContent.trim())
                                }
                                "pdf_unification" -> {
                                    settingsRepo.setPdfUnificationPrompt(newPromptContent.trim())
                                }
                                else -> {
                                    if (editingPrompt != null) {
                                        db.systemPromptDao().updatePrompt(
                                            editingPrompt!!.copy(
                                                name = newPromptName,
                                                content = newPromptContent
                                            )
                                        )
                                    } else {
                                        db.systemPromptDao().insertPrompt(
                                            SystemPromptEntity(
                                                name = newPromptName,
                                                content = newPromptContent
                                            )
                                        )
                                    }
                                }
                            }
                            showAddDialog = false
                            editingPrompt = null
                        }
                    },
                    enabled = when (promptCategory) {
                        "pdf_summary", "pdf_unification" -> newPromptContent.isNotBlank()
                        else -> newPromptName.isNotBlank() && newPromptContent.isNotBlank()
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddDialog = false
                    editingPrompt = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
