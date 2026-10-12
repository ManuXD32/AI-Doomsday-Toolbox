package com.example.llamadroid.ui.ai.llama.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.UnifiedServer
import com.example.llamadroid.data.repository.UnifiedServerKind

/**
 * Entry point for adding or editing a server from any sheet host.
 *
 * Local managed servers (new, "customize first" for a model, or edit) no longer use a reduced form:
 * they open the full-screen [ServerProfileEditorScreen] through [LocalServerProfileEditorOpener] and
 * this sheet closes immediately. What remains here is the "Add server" chooser (button to the local
 * editor, plus the remote form) and the remote/legacy connection editor.
 *
 * @param editing the server to edit, or null to add a new one
 * @param onSaved called with the connection row id (remote saves) before dismissal
 * @param initialModel pre-selects this model for a new local server ("Customize first")
 * @param onManageModels kept for call-site compatibility; model picking now happens in the full editor
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditorSheet(
    viewModel: ServerRegistryViewModel,
    editing: UnifiedServer?,
    onDismiss: () -> Unit,
    onSaved: (rowId: Long?) -> Unit = {},
    onManageModels: () -> Unit = {},
    initialModel: ModelEntity? = null
) {
    val openLocalEditor = LocalServerProfileEditorOpener.current
    val isEdit = editing != null
    val editsManaged = editing?.kind == UnifiedServerKind.LOCAL_MANAGED && editing.cardId != null
    val redirect = editsManaged || (!isEdit && initialModel != null)

    if (redirect) {
        LaunchedEffect(editing?.key, initialModel?.path) {
            openLocalEditor(editing?.cardId, initialModel?.path)
            onDismiss()
        }
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(if (isEdit) R.string.srv_editor_edit_title else R.string.srv_editor_new_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!isEdit) {
                Button(
                    onClick = {
                        openLocalEditor(null, null)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.srv_mode_local), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(
                    stringResource(R.string.spe_chooser_local_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.spe_chooser_remote_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val row by produceState<LlamaServerEntity?>(null, editing?.key) {
                value = editing?.let { viewModel.loadRow(it) }
            }
            if (!isEdit || row != null) {
                key(row?.id) {
                    RemoteServerForm(initialServer = row, onSaved = {
                        onSaved(row?.id)
                        onDismiss()
                    }, onCancel = onDismiss)
                }
            } else {
                CircularProgressIndicator()
            }
        }
    }
}
