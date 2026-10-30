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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppHubRow
import com.example.llamadroid.ui.components.AppHubRowStyle
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.theme.LocalAppVisualStyle
import com.example.llamadroid.ui.theme.sprites.AppSpriteKey
import com.example.llamadroid.ui.navigation.Screen

private data class SettingsEntry(val title: Int, val description: Int, val icon: ImageVector, val route: String, val sprite: AppSpriteKey? = null)
private data class SettingsSection(val header: Int, val entries: List<SettingsEntry>)

/** All preference families remain directly discoverable, grouped by purpose. */
@Composable
fun SettingsHubScreen(navController: NavController) {
    val sections = listOf(
        SettingsSection(R.string.settings_section_app, listOf(
            SettingsEntry(R.string.settings_general, R.string.settings_general_desc, Icons.Default.Tune, "settings_general", AppSpriteKey.SETTINGS)
        )),
        SettingsSection(R.string.settings_section_ai, listOf(
            SettingsEntry(R.string.settings_llm, R.string.settings_llm_desc, Icons.Default.ChatBubbleOutline, "settings_llm", AppSpriteKey.MODEL_LLM),
            SettingsEntry(R.string.settings_hub_imagegen, R.string.settings_imagegen_desc, Icons.Default.Image, "settings_imagegen", AppSpriteKey.MODEL_IMAGE),
            SettingsEntry(R.string.settings_hub_whisper, R.string.settings_whisper_desc, Icons.Default.MicNone, "settings_whisper", AppSpriteKey.MODEL_WHISPER),
            SettingsEntry(R.string.settings_upscaler, R.string.settings_upscaler_desc, Icons.Default.AutoAwesome, "settings_upscaler", AppSpriteKey.TOOL_UPSCALE),
            SettingsEntry(R.string.settings_hub_pdf, R.string.settings_pdf_desc, Icons.Default.Description, "settings_pdf", AppSpriteKey.TOOL_PDF)
        )),
        SettingsSection(R.string.settings_section_agent, listOf(
            SettingsEntry(R.string.settings_hub_agent, R.string.settings_hub_agent_desc, Icons.Default.Terminal, "${Screen.Agent.route}?harnessTab=settings", AppSpriteKey.AGENT_ROBOT),
            SettingsEntry(R.string.settings_hub_agent_runtimes, R.string.settings_hub_agent_runtimes_desc, Icons.Default.Terminal, "${Screen.Agent.route}?harnessTab=runtime", AppSpriteKey.AGENT_ROBOT),
            SettingsEntry(R.string.settings_prompts, R.string.settings_prompts_desc, Icons.Default.EditNote, "settings_prompts", AppSpriteKey.TOOL_NOTES)
        )),
        SettingsSection(R.string.settings_section_system, listOf(
            SettingsEntry(R.string.settings_stats, R.string.settings_stats_desc, Icons.Default.QueryStats, Screen.Stats.route),
            SettingsEntry(R.string.settings_hub_logs, R.string.settings_debug_desc, Icons.Default.BugReport, Screen.Logs.route, AppSpriteKey.TOOL_TERMINAL),
            SettingsEntry(R.string.settings_about, R.string.settings_about_desc, Icons.Default.Info, "about")
        ))
    )
    val listState = rememberLazyListState()
    val tourTargets = LocalWalkthroughTargets.current
    val requestedTarget = tourTargets?.requestedId
    LaunchedEffect(requestedTarget, tourTargets?.retryKey) {
        val route = requestedTarget?.takeIf { it.startsWith("settings.") }?.removePrefix("settings.")
        // Item 0 is the subtitle; each section adds one header item before its entries.
        var index = 1
        for (section in sections) {
            index++
            val hit = section.entries.indexOfFirst { it.route == route }
            if (hit >= 0) { listState.scrollToItem(index + hit); break }
            index += section.entries.size
        }
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
            sections.forEach { section ->
                item(key = "header_${section.header}") {
                    Text(stringResource(section.header), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                }
                items(section.entries, key = { it.route }) { entry ->
                    AppHubRow(
                        title = stringResource(entry.title),
                        subtitle = stringResource(entry.description),
                        icon = entry.icon,
                        sprite = entry.sprite,
                        style = AppHubRowStyle.Plain,
                        onClick = {
                            tourTargets?.recordEvent("settings.${entry.route}")
                            navController.navigate(entry.route)
                        },
                        modifier = Modifier.heightIn(min = 72.dp)
                            .testTag("studio_settings_${entry.route}")
                            .walkthroughTarget("settings.${entry.route}")
                    )
                }
            }
        }
    }
}
