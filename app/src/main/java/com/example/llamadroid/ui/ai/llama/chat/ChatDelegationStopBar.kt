package com.example.llamadroid.ui.ai.llama.chat

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.service.delegation.NativeChatDelegation
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.launch

/** True when the chat's latest DeepSeek task is still active, so the Stop bar should show. */
internal fun delegationStopBarVisible(state: String?): Boolean =
    state != null && state in NativeChatDelegateTaskState.ACTIVE

/** True while the subagent loop is actually executing (spinner); false for paused states. */
internal fun delegationStopBarSpinning(state: String?): Boolean =
    state != null && state in NativeChatDelegateTaskState.LIVE

/**
 * Compact bar above the composer while the chat's DeepSeek task is active: state, title, and a Stop
 * button that confirms before cancelling the subagent and this chat's harness threads. Tapping the
 * rest of the bar opens the delegation viewer.
 */
@Composable
internal fun ChatDelegationStopBar(
    chatId: Long,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(context) { NativeChatDelegation.get(context.applicationContext) }
    val task by remember(chatId) { api.observeLatestTask(chatId) }.collectAsState(initial = null)
    var confirming by remember { mutableStateOf(false) }
    val current = task
    if (current == null || !delegationStopBarVisible(current.state)) return

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag("native_chat_delegation_stop_bar")
            .clickable { navController.navigate(Screen.NativeChatDelegation.createRoute(chatId)) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (delegationStopBarSpinning(current.state)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (delegationStopBarSpinning(current.state)) R.string.native_chat_compaction_stop_bar_running
                        else R.string.native_chat_compaction_stop_bar_paused
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = { confirming = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                modifier = Modifier.testTag("native_chat_delegation_stop_button")
            ) {
                Text(
                    stringResource(R.string.native_chat_compaction_stop_bar_button),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.native_chat_compaction_stop_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.native_chat_compaction_stop_body))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val app = context.applicationContext
                    scope.launch {
                        val message = try {
                            if (api.cancelTask(chatId, reason = "Stopped by the user", stopRuntime = false)) null
                            else app.getString(R.string.native_chat_compaction_stop_nothing)
                        } catch (e: Exception) {
                            app.getString(
                                R.string.native_chat_compaction_stop_failed,
                                e.message ?: e.javaClass.simpleName
                            )
                        }
                        if (message != null) Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.native_chat_compaction_stop_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
