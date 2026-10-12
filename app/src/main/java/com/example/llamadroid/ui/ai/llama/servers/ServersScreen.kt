package com.example.llamadroid.ui.ai.llama.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.ui.ai.llama.LlamaServerSetupGuide
import com.example.llamadroid.ui.components.AppEmptyState
import com.example.llamadroid.ui.components.AppScreenScaffold
import com.example.llamadroid.ui.navigation.Screen
import com.example.llamadroid.ui.walkthrough.LocalWalkthroughTargets
import com.example.llamadroid.ui.walkthrough.walkthroughTarget

/**
 * The unified Servers screen behind both `llama_servers` and `llama_server_list`: one list of
 * every server (local managed, local legacy, remote), a single "+ Add server" action that opens
 * [ServerEditorSheet], and a tap on a row that opens [ServerDetailSheet].
 */
@Composable
fun ServersScreen(
    navController: NavController,
    viewModel: ServerRegistryViewModel = viewModel()
) {
    val servers by viewModel.servers.collectAsState()
    val actions by viewModel.actions.collectAsState()
    val walkthroughTargets = LocalWalkthroughTargets.current
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingKey?.let { key -> servers.firstOrNull { it.key == key } }

    fun openDetail(server: UnifiedServer) {
        detailKey = server.key
        walkthroughTargets?.recordEvent("llama.server_card")
        walkthroughTargets?.recordEvent("llama.server_selection")
    }

    AppScreenScaffold(
        title = stringResource(R.string.srv_screen_title),
        subtitle = stringResource(R.string.srv_screen_subtitle),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(onClick = { editingKey = null; editorOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.srv_add))
            }
        }
    ) { _ ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .walkthroughTarget("llama.servers")
                .walkthroughTarget("llama.server_selection"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "add") {
                Button(
                    onClick = { editingKey = null; editorOpen = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.srv_add), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (servers.isEmpty()) {
                item(key = "empty") {
                    AppEmptyState(
                        title = stringResource(R.string.srv_empty_title),
                        subtitle = stringResource(R.string.srv_empty_subtitle),
                        icon = Icons.Default.Storage
                    )
                }
            }
            items(items = servers, key = { it.key }) { server ->
                UnifiedServerRow(
                    server = server,
                    action = actions[server.key],
                    onClick = { openDetail(server) },
                    onStart = { viewModel.start(server) },
                    onStop = { viewModel.stop(server) }
                )
            }
            item(key = "guide") {
                LlamaServerSetupGuide(onOpenSettings = { navController.navigate("settings_llm") })
            }
        }
    }

    detailKey?.let { key ->
        ServerDetailSheet(
            serverKey = key,
            viewModel = viewModel,
            onDismiss = { detailKey = null },
            onEdit = { server ->
                detailKey = null
                editingKey = server.key
                editorOpen = true
            },
            onOpenChat = { chatId, rowId ->
                detailKey = null
                navController.navigate(Screen.LlamaChat.createRoute(chatId, rowId))
            },
            onOpenWebUi = { port ->
                detailKey = null
                navController.navigate("chat?port=$port")
            }
        )
    }

    if (editorOpen && (editingKey == null || editing != null)) {
        ServerEditorSheet(
            viewModel = viewModel,
            editing = editing,
            onDismiss = { editorOpen = false; editingKey = null },
            onManageModels = {
                editorOpen = false
                navController.navigate(Screen.LLMModels.route)
            }
        )
    }
}
