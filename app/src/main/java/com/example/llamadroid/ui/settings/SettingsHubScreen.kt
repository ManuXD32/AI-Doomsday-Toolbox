package com.example.llamadroid.ui.settings

import com.example.llamadroid.ui.walkthrough.walkthroughTarget
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.navigation.Screen

private data class SettingsEntry(val title: Int, val description: Int, val icon: ImageVector, val route: String)

/** All preference families remain directly discoverable, including task-specific defaults. */
@Composable
fun SettingsHubScreen(navController: NavController) {
    val entries = listOf(
        SettingsEntry(R.string.settings_general, R.string.settings_general_desc, Icons.Default.Tune, "settings_general"),
        SettingsEntry(R.string.llama_cards_title, R.string.settings_llama_servers_desc, Icons.Default.Dns, Screen.LlamaServers.route),
        SettingsEntry(R.string.settings_llm, R.string.settings_llm_desc, Icons.Default.ChatBubbleOutline, "settings_llm"),
        SettingsEntry(R.string.settings_imagegen, R.string.settings_imagegen_desc, Icons.Default.Image, "settings_imagegen"),
        SettingsEntry(R.string.whisper_settings_title, R.string.settings_whisper_desc, Icons.Default.MicNone, "settings_whisper"),
        SettingsEntry(R.string.settings_upscaler, R.string.settings_upscaler_desc, Icons.Default.AutoAwesome, "settings_upscaler"),
        SettingsEntry(R.string.settings_pdf, R.string.settings_pdf_desc, Icons.Default.Description, "settings_pdf"),
        SettingsEntry(R.string.settings_prompts, R.string.settings_prompts_desc, Icons.Default.EditNote, "settings_prompts"),
        SettingsEntry(R.string.settings_debug, R.string.settings_debug_desc, Icons.Default.Terminal, Screen.Logs.route),
        SettingsEntry(R.string.settings_stats, R.string.settings_stats_desc, Icons.Default.QueryStats, Screen.Stats.route),
        SettingsEntry(R.string.settings_about, R.string.settings_about_desc, Icons.Default.Info, "about")
    )
    val listState = rememberLazyListState()
    val tourTargets = LocalWalkthroughTargets.current
    val requestedTarget = tourTargets?.requestedId
    LaunchedEffect(requestedTarget, tourTargets?.retryKey) {
        val route = requestedTarget?.takeIf { it.startsWith("settings.") }?.removePrefix("settings.")
        val index = entries.indexOfFirst { it.route == route }
        if (index >= 0) listState.scrollToItem(index + 1)
    }
    AppScreenScaffold(title = stringResource(R.string.settings_title), onBack = { navController.popBackStack() }) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(stringResource(R.string.settings_subtitle), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            }
            items(entries, key = { it.route }) { entry ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 72.dp)
                            .testTag("studio_settings_${entry.route}").walkthroughTarget("settings.${entry.route}")
                            .clickable { navController.navigate(entry.route) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(entry.title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(entry.description), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
