package com.example.llamadroid.ui.settings.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.harness.HarnessKvCacheSettings
import com.example.llamadroid.ui.ai.llama.LlamaChatViewModel
import com.example.llamadroid.ui.ai.llama.LlamaChatViewModelFactory
import com.example.llamadroid.ui.ai.llama.LlamaPromptProfilesManagerDialog
import com.example.llamadroid.ui.ai.llama.servers.ServerEditorSheet
import com.example.llamadroid.ui.ai.llama.servers.ServerRegistryViewModel

/** "New chats": prompt profiles plus the per-server "defaults for new chats" editors. */
@Composable
internal fun NewChatsSection(serversViewModel: ServerRegistryViewModel) {
    val context = LocalContext.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val repository = remember(database) {
        LlamaRepository(
            database.llamaServerDao(),
            database.llamaChatDao(),
            database.llamaChatFolderDao(),
            database.llamaMessageDao(),
            database.llamaChatPromptProfileDao()
        )
    }
    val viewModel: LlamaChatViewModel = viewModel(factory = LlamaChatViewModelFactory(repository))
    val profiles by viewModel.promptProfiles.collectAsState()
    val servers by serversViewModel.servers.collectAsState()
    val saveError = stringResource(R.string.llama_prompt_profile_save_error)
    var showProfiles by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<UnifiedServer?>(null) }

    OutlinedButton(onClick = { showProfiles = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.llama_prompt_profiles_manage), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    SectionHint(R.string.chat_global_new_chats_servers_hint)
    if (servers.isEmpty()) SectionHint(R.string.chat_global_new_chats_no_servers)
    servers.forEach { server ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { editing = server }) {
                    Text(stringResource(R.string.chat_global_new_chats_edit_defaults), maxLines = 2)
                }
            }
        }
    }
    if (showProfiles) {
        LlamaPromptProfilesManagerDialog(
            customProfiles = profiles,
            onDismiss = { showProfiles = false },
            onCreateProfile = { name, content ->
                viewModel.createPromptProfile(name, content) { ok ->
                    if (!ok) Toast.makeText(context, saveError, Toast.LENGTH_SHORT).show()
                }
            },
            onUpdateProfile = { profile, name, content ->
                viewModel.updatePromptProfile(profile, name, content) { ok ->
                    if (!ok) Toast.makeText(context, saveError, Toast.LENGTH_SHORT).show()
                }
            },
            onDeleteProfile = { viewModel.deletePromptProfile(it) }
        )
    }
    editing?.let { server ->
        ServerEditorSheet(viewModel = serversViewModel, editing = server, onDismiss = { editing = null })
    }
}

private const val GIB = 1024L * 1024 * 1024
private val KV_BUDGET_OPTIONS_GIB = listOf(1, 2, 4, 8, 16)

/** "Delegation": the harness KV-cache-on-disk switch and budget (prefs `harness_kv_cache_settings`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DelegationSection() {
    val context = LocalContext.current
    val kv = remember(context) { HarnessKvCacheSettings.get(context) }
    val enabled by kv.enabled.collectAsState()
    val budget by kv.budgetBytes.collectAsState()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.harness_kv_cache_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.harness_kv_cache_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = kv::setEnabled)
            }
            Text(stringResource(R.string.harness_kv_cache_budget), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KV_BUDGET_OPTIONS_GIB.forEach { gib ->
                    FilterChip(
                        selected = budget == gib * GIB,
                        onClick = { kv.setBudgetBytes(gib * GIB) },
                        enabled = enabled,
                        label = { Text(stringResource(R.string.harness_kv_cache_budget_option, gib), maxLines = 1) }
                    )
                }
            }
            SectionHint(R.string.chat_global_delegation_clear_hint)
        }
    }
}
