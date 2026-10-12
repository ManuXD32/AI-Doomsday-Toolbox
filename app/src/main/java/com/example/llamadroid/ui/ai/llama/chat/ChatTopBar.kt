package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.repository.UnifiedServerStatus
import com.example.llamadroid.ui.ai.llama.servers.ServerStatusDot
import com.example.llamadroid.ui.walkthrough.FeatureGuideAction

/** What the compact server chip under the title shows. */
internal data class ChatServerChip(val label: String, val status: UnifiedServerStatus?)

/**
 * Compact chat top bar: Back, tappable title (rename), server chip, Call, feature guide, Chat settings
 * and a ⋮ menu holding the rest. Title and chip are single-line with ellipsis so the bar fits 320dp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(
    title: String,
    serverChip: ChatServerChip?,
    isCallActive: Boolean,
    callEnabled: Boolean,
    isSearching: Boolean,
    showWorkspace: Boolean,
    pinned: Boolean,
    pinEnabled: Boolean,
    clearEnabled: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onServerChip: () -> Unit,
    onChangeServer: () -> Unit,
    onCall: () -> Unit,
    onSettings: () -> Unit,
    onToggleSearch: () -> Unit,
    onWorkspace: () -> Unit,
    onPin: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onSaveAsNote: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Below 360dp the Call action moves into the menu so the title and server chip keep room.
    val density = LocalDensity.current
    val compact = LocalWindowInfo.current.containerSize.width / density.density < 360f
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(onClickLabel = stringResource(R.string.native_chat_rename_action), onClick = onRename)
                        .testTag("native_chat_title")
                )
                ServerChipView(serverChip = serverChip, onClick = onServerChip)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            if (!compact) {
                IconButton(onClick = onCall, enabled = callEnabled) {
                    Icon(
                        painter = painterResource(android.R.drawable.sym_action_call),
                        contentDescription = stringResource(R.string.llama_call_mode),
                        tint = if (isCallActive) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
            }
            // The shared help action keeps the feature-guide tag and walkthrough target every screen uses.
            FeatureGuideAction()
            IconButton(onClick = onSettings, modifier = Modifier.testTag("native_chat_settings")) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.native_chat_settings_title))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.llama_chat_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(if (isSearching) R.string.native_chat_menu_search_close else R.string.llama_search_chat)
                            )
                        },
                        leadingIcon = {
                            Icon(if (isSearching) Icons.Default.Close else Icons.Default.Search, contentDescription = null)
                        },
                        onClick = { menuOpen = false; onToggleSearch() }
                    )
                    if (showWorkspace) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.native_chat_workspace_open)) },
                            leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                            onClick = { menuOpen = false; onWorkspace() }
                        )
                    }
                    if (compact) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.llama_call_mode)) },
                            enabled = callEnabled,
                            leadingIcon = {
                                Icon(painterResource(android.R.drawable.sym_action_call), contentDescription = null)
                            },
                            onClick = { menuOpen = false; onCall() }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (pinned) R.string.native_chat_unpin_watch else R.string.native_chat_pin_watch
                                )
                            )
                        },
                        enabled = pinEnabled,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = if (pinned) Color(0xFFFFC107) else LocalContentColor.current
                            )
                        },
                        onClick = { menuOpen = false; onPin() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.native_chat_change_server), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                        onClick = { menuOpen = false; onChangeServer() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.llama_clear_chat)) },
                        enabled = clearEnabled,
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onClear() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.llama_export_chat)) },
                        onClick = { menuOpen = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.llama_save_chat_as_note)) },
                        onClick = { menuOpen = false; onSaveAsNote() }
                    )
                }
            }
        }
    )
}

@Composable
private fun ServerChipView(serverChip: ChatServerChip?, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.testTag("native_chat_server_chip")
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ServerStatusDot(serverChip?.status ?: UnifiedServerStatus.UNKNOWN, size = 8.dp)
            Text(
                text = serverChip?.label ?: stringResource(R.string.native_chat_server_chip_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}
