package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R

/**
 * Chat settings as a scrollable bottom sheet with collapsible sections. Every valid change is
 * persisted by the screen's auto-save effect, so there are no Cancel/Save buttons and closing the
 * sheet loses nothing.
 *
 * @param serverSummary one-line description of the current server for the first section header
 * @param onOpenServerSheet closes this sheet and opens the chat server sheet
 * @param memorySection optional slot for the per-chat memory (KV cache) controls; when null the
 *        Memory section is not shown
 * @param delegationSection optional slot for the DeepSeek runtime (delegation) controls; when null
 *        the group is not shown
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSettingsSheet(
    state: ChatSettingsState,
    env: ChatSettingsEnv,
    serverSummary: String,
    onOpenServerSheet: () -> Unit,
    onChangeServer: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    memorySection: (@Composable () -> Unit)? = null,
    delegationSection: (@Composable () -> Unit)? = null
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    if (state.activeServerIsLiteRt) R.string.litert_gallery_model_configs else R.string.native_chat_settings_title
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.native_chat_settings_autosave_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ChatSettingsGroupList(
                state = state,
                env = env,
                serverSummary = serverSummary,
                onOpenServerSheet = onOpenServerSheet,
                onChangeServer = onChangeServer,
                memorySection = memorySection,
                delegationSection = delegationSection
            )
        }
    }
}
