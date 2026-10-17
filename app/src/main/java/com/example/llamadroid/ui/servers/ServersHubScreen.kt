package com.example.llamadroid.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.AiToolServerService
import com.example.llamadroid.service.SSHService
import com.example.llamadroid.ui.ai.llama.chatlist.ChatListServerSheetState
import com.example.llamadroid.ui.ai.llama.chatlist.ChatListServerSheets
import com.example.llamadroid.ui.ai.llama.chatlist.ChatsServerStrip
import com.example.llamadroid.ui.ai.llama.servers.LocalServerProfileEditorOpener
import com.example.llamadroid.ui.ai.llama.servers.ServerRegistryViewModel
import com.example.llamadroid.ui.ai.llama.servers.key
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppInfoRow
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppSectionTitle
import com.example.llamadroid.ui.dashboard.DashboardFileServerCard
import com.example.llamadroid.ui.dashboard.DashboardKiwixCard
import com.example.llamadroid.ui.navigation.Screen

/** Route of the merged Servers hub (registered by the app shell). */
const val SERVERS_HUB_ROUTE = "servers_hub"

/**
 * One place for every server the app can run: model servers, sharing (browser, files, wiki),
 * Ollama, distributed inference and Termux services. Sections are lazy list items, so heavy cards
 * (file server, Kiwix) only compose when scrolled into view. Existing screens keep their routes.
 */
@Composable
fun ServersHubScreen(navController: NavController) {
    val serversViewModel: ServerRegistryViewModel = viewModel()
    val openLocalServerEditor = LocalServerProfileEditorOpener.current
    val servers by serversViewModel.servers.collectAsState()
    val serverActions by serversViewModel.actions.collectAsState()
    val sheets = remember { ChatListServerSheetState() }

    fun open(route: String) {
        navController.navigate(route) { launchSingleTop = true }
    }

    AppScreenScaffold(
        title = stringResource(R.string.servers_hub_title),
        subtitle = stringResource(R.string.servers_hub_subtitle),
        onBack = { navController.popBackStack() }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = AppChromeDefaults.ScreenPadding,
                end = AppChromeDefaults.ScreenPadding,
                top = 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "models_title") {
                AppSectionTitle(
                    title = stringResource(R.string.servers_hub_models_title),
                    supporting = stringResource(R.string.servers_hub_models_subtitle)
                )
            }
            item(key = "models_strip") {
                ChatsServerStrip(
                    servers = servers,
                    actions = serverActions,
                    onOpen = { server -> sheets.detailKey = server.key },
                    onStart = serversViewModel::start,
                    onStop = serversViewModel::stop,
                    onAddServer = { openLocalServerEditor(null, null) },
                    onAddRemote = { sheets.openEditor() },
                    onGetModel = { open(Screen.LLMModels.route) },
                    onOpenWebUi = { server -> open("${Screen.Chat.route}?port=${server.port}") }
                )
            }

            item(key = "sharing_title") {
                AppSectionTitle(
                    title = stringResource(R.string.servers_hub_sharing_title),
                    supporting = stringResource(R.string.servers_hub_sharing_subtitle)
                )
            }
            item(key = "share_browser") { ShareToBrowserSummary(onOpen = { open(Screen.AiServersHub.route) }) }
            item(key = "file_server") { DashboardFileServerCard() }
            item(key = "kiwix_server") { DashboardKiwixCard(navController) }

            item(key = "ollama") { OllamaSection(onManage = { open(Screen.OllamaManager.route) }) }

            item(key = "distributed") {
                HubSectionCard(
                    title = stringResource(R.string.servers_hub_distributed_title),
                    subtitle = stringResource(R.string.servers_hub_distributed_subtitle)
                ) {
                    HubLinkRow(
                        title = stringResource(R.string.dashboard_distributed_title),
                        summary = stringResource(R.string.dashboard_setup_distributed_desc),
                        onClick = { open(Screen.DistributedHub.route) }
                    )
                    HubLinkRow(
                        title = stringResource(R.string.dashboard_sd_distributed_title),
                        summary = stringResource(R.string.dashboard_sd_distributed_desc),
                        onClick = { open(Screen.SdDistributedHub.route) }
                    )
                    HubLinkRow(
                        title = stringResource(R.string.servers_hub_run_settings_title),
                        summary = stringResource(R.string.servers_hub_run_settings_desc),
                        onClick = { open(Screen.SdDistributedRunConfig.route) }
                    )
                }
            }

            item(key = "termux") { TermuxSection(onOpen = { open(Screen.Termux.route) }) }
        }
    }

    ChatListServerSheets(state = sheets, viewModel = serversViewModel, navController = navController)
}

@Composable
private fun ShareToBrowserSummary(onOpen: () -> Unit) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val configs by database.aiServerDao().observeConfigs().collectAsState(initial = emptyList())
    val runtimeStates by AiToolServerService.runtimeStates.collectAsState()
    HubSectionCard(
        title = stringResource(R.string.servers_hub_share_browser_title),
        subtitle = stringResource(R.string.servers_hub_share_browser_subtitle)
    ) {
        AppInfoRow(
            label = stringResource(R.string.servers_hub_share_browser_status),
            value = stringResource(
                R.string.ai_servers_running_badge,
                runtimeStates.count { it.running },
                configs.size
            )
        )
        HubOpenButton(label = stringResource(R.string.servers_hub_open), onClick = onOpen)
    }
}

@Composable
private fun OllamaSection(onManage: () -> Unit) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val saved by database.ollamaServerDao().getAllServers().collectAsState(initial = emptyList())
    HubSectionCard(
        title = stringResource(R.string.servers_hub_ollama_title),
        subtitle = stringResource(R.string.servers_hub_ollama_subtitle)
    ) {
        AppInfoRow(
            label = stringResource(R.string.servers_hub_ollama_saved),
            value = saved.size.toString()
        )
        HubOpenButton(label = stringResource(R.string.servers_hub_manage), onClick = onManage)
    }
}

@Composable
private fun TermuxSection(onOpen: () -> Unit) {
    val connected by SSHService.isConnected.collectAsState()
    HubSectionCard(
        title = stringResource(R.string.servers_hub_termux_title),
        subtitle = stringResource(R.string.servers_hub_termux_subtitle)
    ) {
        AppInfoRow(
            label = stringResource(R.string.servers_hub_termux_connection),
            value = stringResource(
                if (connected) R.string.servers_hub_termux_connected else R.string.servers_hub_termux_disconnected
            ),
            highlight = connected
        )
        HubLinkRow(
            title = stringResource(R.string.hub_termux),
            summary = stringResource(R.string.hub_termux_desc),
            onClick = onOpen
        )
    }
}

@Composable
private fun HubSectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    AppSectionCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        content()
    }
}

@Composable
private fun HubLinkRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedButton(onClick = onClick) {
            Text(stringResource(R.string.servers_hub_open), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HubOpenButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
