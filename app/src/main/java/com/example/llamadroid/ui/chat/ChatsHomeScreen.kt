package com.example.llamadroid.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.ui.ai.llama.EasyLlamaChatEntryCard
import com.example.llamadroid.ui.ai.llama.LlamaChatListContent
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppContentColumn
import com.example.llamadroid.ui.components.AppPageBackground
import com.example.llamadroid.ui.components.AppPageHeader
import com.example.llamadroid.ui.components.AppScrollableTabRow
import com.example.llamadroid.ui.navigation.Screen

/**
 * Chats root: native chat history plus Agent (Harness) projects. Server/scheduler management is
 * reachable from the overflow menu; the canonical screens keep owning their own state.
 */
@Composable
fun ChatsHomeScreen(navController: NavController) {
    val context = LocalContext.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var newChatSignal by rememberSaveable { mutableIntStateOf(0) }
    var showOverflow by remember { mutableStateOf(false) }
    var showNewMenu by remember { mutableStateOf(false) }

    AppPageBackground {
        AppContentColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppPageHeader(
                title = stringResource(R.string.ui_chats_title),
                trailing = {
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.ui_chats_more)
                            )
                        }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_chats_local_servers), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = { showOverflow = false; navController.navigate(Screen.LlamaServers.route) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_chats_server_connections), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = { showOverflow = false; navController.navigate(Screen.LlamaServerList.route) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_chats_scheduled_prompts), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = { showOverflow = false; navController.navigate(Screen.LlamaScheduler.route) }
                            )
                        }
                    }
                }
            )

            AppScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.ui_chats_tab_chats), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.ui_chats_tab_agent), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (selectedTab == 0) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        EasyLlamaChatEntryCard(
                            showQuickSetup = true,
                            onChatOpened = { chatId, serverId ->
                                navController.navigate(Screen.LlamaChat.createRoute(chatId, serverId))
                            },
                            onManageModels = { navController.navigate(Screen.LLMModels.route) }
                        )
                        LlamaChatListContent(
                            navController = navController,
                            folderId = null,
                            modifier = Modifier.weight(1f),
                            embedded = true,
                            newChatSignal = newChatSignal
                        )
                    }
                } else {
                    val projects by database.agentChatDao().getAllConversations()
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "open_agent") {
                            ChatsLinkRow(
                                title = stringResource(R.string.ui_chats_open_agent),
                                supporting = stringResource(R.string.ui_chats_open_agent_desc),
                                onClick = { navController.navigate(Screen.Agent.route) }
                            )
                        }
                        if (projects.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    text = stringResource(R.string.ui_chats_agent_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                        items(projects, key = { "project_${it.id}" }) { project ->
                            ChatsLinkRow(
                                title = project.title.ifBlank { project.projectFolder },
                                supporting = project.projectFolder,
                                onClick = {
                                    navController.navigate(Screen.Agent.createRoute(project.id))
                                }
                            )
                        }
                        item(key = "fab_space") { Box(modifier = Modifier.heightIn(min = 72.dp)) }
                    }
                }

                Box(modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp)) {
                    ExtendedFloatingActionButton(
                        onClick = { showNewMenu = true },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.ui_chats_new), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                    DropdownMenu(expanded = showNewMenu, onDismissRequest = { showNewMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_chats_new_chat), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                showNewMenu = false
                                selectedTab = 0
                                newChatSignal += 1
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_chats_new_agent), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                showNewMenu = false
                                navController.navigate(Screen.Agent.route)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatsLinkRow(title: String, supporting: String?, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = AppChromeDefaults.InnerCardShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!supporting.isNullOrBlank()) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
